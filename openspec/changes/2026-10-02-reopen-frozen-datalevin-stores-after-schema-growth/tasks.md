## 1. Reproduce

- [x] 1.1 Bootstrap, freeze and populate a store with the module's attribute set from before wildcard subjects, close it, and reopen it through `eacl.datalevin.schema/create-conn` (`eacl.datalevin.store-upgrade-test`); confirm it fails with `:datalevin/frozen-attribute-write` on the unmodified module
- [x] 1.2 Reproduce the same failure for an application attribute added to `extra-schema` after bootstrap
- [x] 1.3 Record what Datalevin and EACL each admit and refuse on the unmodified code (`design.md`, Context)

## 2. Open

- [x] 2.1 Declare only the application's schema to Datalevin in `create-conn`
- [x] 2.2 Add the module's missing attributes in `create-conn` only while no write policy is installed, treating a store that is not embedded as one without a readable policy
- [x] 2.3 Confirm a fresh store gets the same attribute definitions as before

## 3. Admit growth

- [x] 3.1 Let `expected-write-policy` describe the policy over a given attribute set
- [x] 3.2 Obtain the admission token by presenting the module's policy for the covered attributes; refuse attributes the module does not declare and attributes that hold data
- [x] 3.3 Decide admission, and check source identity and generation evidence, before adding attributes; extend the policy with the token afterwards
- [x] 3.4 Name missing, uncovered and populated attributes in `:eacl.datalevin/write-policy-drift`

## 4. Verify

- [x] 4.1 Tests for the earlier store, an earlier store whose policy covers other attributes, the application attribute, an interrupted extension, data outside the policy, a foreign policy, an undeclared attribute, a store without source identity, a store with incomplete generations, a store that is not embedded, a store without a policy, and the earlier attribute set after the extension
- [x] 4.2 Apply each defect the tests are meant to catch to the source by hand and confirm a test fails
- [x] 4.3 Datalevin module suite in the Datalevin nREPL; CI-equivalent battery and DataScript ClojureScript build; `bin/formal source-closure`

## 5. Document

- [x] 5.1 Module README and PORTING notes: growth under the write policy, the refused cases, an interrupted extension, and opening stores with `create-conn`
- [x] 5.2 Release notes: Datalevin extends the write policy of a store bootstrapped before the release
