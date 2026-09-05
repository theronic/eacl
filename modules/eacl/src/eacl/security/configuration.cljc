(ns eacl.security.configuration
  "Static and shared controller scopes for client protected formats."
  (:require [eacl.secure-format :as secure]
            [eacl.security.keyring :as keyring]))

(def primary-keys
  [:security-keyring-controller :security-key :security-keyring :security-kid])
(def zed-keys
  [:zed-token-keyring-controller :zed-token-key :zed-token-keyring :zed-token-kid])

(defn- invalid! [scope reason]
  (throw (ex-info "Invalid EACL security key configuration."
                  {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                   :key scope :reason reason})))

(defn- scope-controller [options [controller-option key-option ring-option kid-option]]
  (let [present? #(contains? options %)
        static? (some present? [key-option ring-option kid-option])
        controller (get options controller-option)
        kid (get options kid-option :default)]
    (cond
      (present? controller-option)
      (do
        (when static? (invalid! controller-option :mixed-key-sources))
        (when-not (keyring/keyring? controller) (invalid! controller-option :invalid-controller))
        controller)

      :else
      (do
        (when (and (present? key-option) (present? ring-option))
          (invalid! key-option :mixed-key-sources))
        (when (and (not= key-option :security-key)
                   (not-any? present? [key-option ring-option]))
          (invalid! key-option :missing-key-material))
        (let [keys (cond
                     (present? ring-option) (get options ring-option)
                     (present? key-option) {kid (get options key-option)}
                     :else (do (secure/warn-defaulted-token-key!)
                               {:default secure/default-root-key}))]
          (try
            (keyring/keyring {:keys keys :active-kid kid})
            (catch #?(:clj Throwable :cljs :default) _
              ;; Do not retain a secret-bearing input, printable id, or cause.
              (invalid! (if (present? ring-option) ring-option key-option)
                        :invalid-keyring))))))))

(defn format-scopes
  "Constructs private static controllers or retains explicitly shared ones.
   An absent dedicated Zed scope shares the primary controller."
  [options]
  (let [primary (scope-controller options primary-keys)
        zed (if (some #(contains? options %) zed-keys)
              (scope-controller options zed-keys)
              primary)]
    {:format-options {:keyring-controller primary}
     :zed-token-format-options {:keyring-controller zed}}))
