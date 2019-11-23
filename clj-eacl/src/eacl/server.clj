(ns eacl.server
  (:require
    [taoensso.timbre :as log]
    [eacl.core :as eacl]
    [eacl.utils :refer [spy profile]]
    [clojure.core.async :refer [chan]]
    [reitit.core :as r]
    [reitit.ring :as ring]
    [reitit.ring.coercion :as rrc]
    [reitit.coercion.spec]
    [org.httpkit.client :as http]
    [datascript.core :as d]
    [taoensso.carmine :as redis :refer (wcar)]
    [ring.adapter.jetty :as jetty]))


;function can(auth_req: AccessRequest): boolean {
;	let db = ds.db(conn);
;
;	let qry =
;		`[:find ?user ?group
;		 :in $ % ?user ?action ?resource ?location
;		 :where
;		 [?group :eacl/permission ?perm]
;		 [?perm :eacl/resource ?resource]
;		 [?perm :eacl/action ?action]
;		 (child-of ?user ?group)
;		 ]`;
;
;	let results = ds.q(
;		qry,
;		db,
;		child_rules,
;		auth_req.user,
;		auth_req.action,
;		auth_req.resource);
;
;	client.get('my_key', x => console.log);
;	console.log(client.get('my_key'));
;	return false // not allowed!
;}
;
;function demand(req: AccessRequest) {
;	if (!can(req)) {
;		throw "Access Denied" // configure message and level of detail
;	}
;}



(comment
  (let [conn (d/create-conn eacl-schema)
        user (create-user! conn {:user/name "Petrus Theron"})]
    ()
    (grant-access! conn {:eacl/user user})
    (can-user? @conn {:eacl/user user
                      :eacl/path "invoices/*" ;; hmm
                      :eacl/location "Cape Town"
                      :eacl/action :read})))

;export function create_user(conn, user) {
;	let tx_report = ds.transact(conn,
;		[{
;			":db/id": -1,
;			":user/name": "Test User",
;			":user/group":  -1, // point at self for group resolution.
;		}]);
;	// todo handle errors
;	let user_id = ds.resolve_tempid(tx_report.tempids, -1);;
;	return user_id;
;}
;
;export function grant_user_access(conn, m) {
;	ds.transact(conn,
;		[{
;			":db/id": -1, // permission.
;			":eacl/resource": m.resource,
;			":eacl/action": m.action,
;			":eacl/permission": m.user, // group?
;			//"user/group":  -1, // point at self for group resolution.
;			":eacl/user": m.user,
;		}]);
;};



(defn rand-uuid []
  (java.util.UUID/randomUUID))

(def redis-spec {:pool {} :spec {:host "localhost"}})
(defmacro wcar* [& body] `(redis/wcar redis-spec ~@body))
;(defmacro profile [ks & body]
;  `())

(defmacro async-acl
  [req & body]
  (log/debug "Async acl called for " req)
  `(do ~@body))

(def allowed? false)

(defn timeout [timeout-ms callback]
  (let [fut (future (callback))
        ret (deref fut timeout-ms ::timed-out)]
    (when (= ret ::timed-out)
      (future-cancel fut))
    ret))

(comment (timeout 900 #(Thread/sleep 1000)))

(defn side-effecting? [req]
  (= :get (:request-method req))) ;; just for now.

(defn allowed? [user req]
  (let [res (wcar*
              (redis/bitop "AND" "cap-temp" "a-page" "user-b")
              (redis/bitop "XOR" "cap-temp" "a-page" "cap-temp")
              (redis/bitcount "cap-temp"))
        bit-count (last res)]
    (log/debug res)
    (zero? bit-count)))

(def test-user :user/petrus)

(defn eacl-blocked-test-route-handler [req]
  {:status 401
   :body   "Not allowed!"})

(defn eacl-allowed-test-route-handler [req]
  {:status 200
   :body   (pr-str {:eacl/allowed (get-in req [:params :eacl/token])})})

(def eacl-middleware
  "Middleware for asynchronous enterprise access control."
  {:name    ::eacl-reitit-middleware
   :compile (fn [{:as input :keys []} _]
              (log/debug "middleware input:" input)
              (fn [handler] ;; todo dispatch on method type
                (fn [req]
                  (if (side-effecting? req)
                    (do
                      (log/debug "do sync ACL")
                      (if (allowed? test-user req)
                        (handler req)
                        (eacl-blocked-test-route-handler req)))
                    (do
                      (log/debug "trying async ACL")
                      (if (allowed? test-user req)
                        (handler req)
                        (eacl-blocked-test-route-handler req)))))))})

(def app
  (ring/ring-handler
    (ring/router
      [["/invoices/:id" {:get {
                               ;:parameters {:query {:id int?}}
                               :handler    (fn [req] ;{:as req {invoice-id :id} :params :keys [session]}
                                             ;(async-acl [req])
                                             {:status 200
                                              :body "hi!" #_(str (wcar*
                                                                   (redis/get (str "invoices:" 123))))})}}]
       ["/" {:get {:handler (fn [req]
                              (let [res (wcar redis-spec
                                          (redis/get "my_key"))]
                                {:body (str "there" res)}))}}]
       ["/eacl"
        ["/allowed" {:handler eacl-allowed-test-route-handler}]
        ["/blocked" {:handler eacl-blocked-test-route-handler}]]]
      {:data {:coercion   reitit.coercion.spec/coercion
              :middleware [
                           eacl-middleware
                           rrc/coerce-exceptions-middleware
                           rrc/coerce-request-middleware
                           rrc/coerce-response-middleware]}})
    (some-fn
      (ring/create-default-handler
        {:not-found (fn [req] {:status 404, :body "Not found", :headers {}})}))))

(defn ensure-eacl-app-secure! [app]
  (let [blocked-req           {:request-method :get
                               :params {:eacl/token (rand-uuid)}
                               :uri            "/eacl/blocked"}
        expected-blocked-resp (eacl-blocked-test-route-handler blocked-req)
        allowed-req           {:request-method :get
                               :params {:eacl/token (rand-uuid)}
                               :uri            "/eacl/allowed"}
        expected-allowed-resp (eacl-allowed-test-route-handler allowed-req)
        actual-allowed-resp   (app allowed-req)
        actual-blocked-resp   (app blocked-req)]
    ;; instead of fall through?
    (if (and (= actual-blocked-resp expected-blocked-resp)
             (= actual-allowed-resp expected-allowed-resp))
      (do
        (log/info "Enterprise Access Control is up.")
        :eacl.status/up)
      (do
        (log/error "Enterprise Access Control is not secure!")
        (when (not= expected-blocked-resp (app blocked-req))
          (throw (Exception. "For security, Enterprise Access Control expects a blocking route at `/eacl/blocked`.")))
        (when (not= expected-allowed-resp (app allowed-req))
          (throw (Exception. "For security, Enterprise Access Control expects a working route that always allows at `/eacl/allowed`.")))))))

(defonce !server (atom nil))

(defn ^:export main [& args]
  (when-let [server @!server]
    (log/debug "Stopping server...")
    (.stop server))
  (ensure-eacl-app-secure! #'app)
  (log/debug "Starting server...")
  (reset! !server (jetty/run-jetty #'app {:port 3000, :join? false})))

(comment
  (profile :redis10
           (do (wcar*
                 (doseq [x (range 10000)]
                   (redis/bitfield "my_key"
                                   "SET" "u1" x 1)
                   (redis/bitfield "my_key"
                                   "GET" "i8" x)))
               nil))

  (use 'clojure.repl)
  (doc redis/bitfield)
  (wcar* (redis/bitfield "my_key" "SET" "u8" (str "#" 510) 255))
  (wcar* (redis/bitfield "my_key" "GET" "u8" (str "#" 510)))
  (doc redis/bitop)
  (wcar* (redis/bitfield "my_key" "GET" "u8" 500))
  (profile :redis1
           (let [ents (range 1000000)]
                 ;txf  (fn [x] ["SET" "u8" x (mod x 127)])]
                 ;args (apply concat (map txf ents))]
             (wcar*
               (doseq [x (vec ents)]
                 (redis/bitfield "my_key" "SET" "u8" (str "#" x) (mod x 255))))
               ;(redis/bitfield "my_key" "GET" "u8" 127)
               ;(redis/bitfield "my_key" "GET" "u8" 140)
               ;(redis/bitfield "my_key" "SET" "u8" 0 123)
               ;(redis/bitfield "my_key" "SET" "u8" 500 124))
             (profile :test (wcar* (redis/bitfield "my_key" "GET" "u8" (str "#" 5000))))))
                 ;(apply redis/bitfield "my_key" args))
               ;(wcar*
               ;  (redis/bitfield "my_key" "GET" "u8" 100)))))
  (profile :redis1
           (wcar redis-spec
             (redis/bitfield "a-page" "SET" "u1" 0 1 "SET" "u1" 8 1 "SET" "u7" 9 0)
             (redis/bitfield "a-page:level" "SET" "u7" 9 60)
             (redis/bitfield "user-b" "SET" "u1" 0 1 "SET" "u1" 8 1 "SET" "u7" 9 60)
             (redis/bitfield "user-c" "SET" "u1" 0 1 "SET" "u1" 8 1 "SET" "u7" 9 40)
             (redis/bitfield "user-d" "SET" "u1" 8 1 "SET" "u7" 9 60)))

  (profile :nothing (wcar redis-spec (redis/get "my_key")))
  (profile :redis-nothing (wcar redis-spec))
  (profile :redis2
           (wcar redis-spec
             (redis/bitop "AND" "cap-temp" "a-page" "user-b")
             (redis/bitop "XOR" "cap-temp" "a-page" "cap-temp")
             (redis/bitcount "cap-temp")
             (redis/bitfield "a-page:level" "GET" "u7" 9)
             (redis/bitfield "user-b" "GET" "u7" 9)))
  (main)
  (app {:request-method :get
        :uri "/"}))