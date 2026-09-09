(ns eacl.security.protocols
  "Internal capture boundary; keeps codecs independent of controller construction.")

(defprotocol KeyringSource
  (-snapshot [controller] "Captures one complete private keyring state.")
  (-derive-key [controller snapshot kid root-key domain version]
    "Derives or reuses a domain key within the captured generation only."))
