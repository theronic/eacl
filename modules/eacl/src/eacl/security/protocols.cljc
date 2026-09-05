(ns eacl.security.protocols
  "Internal capture boundary; keeps codecs independent of controller construction.")

(defprotocol KeyringSource
  (-snapshot [controller] "Captures one complete private keyring state."))
