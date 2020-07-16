# **EACL**: Enterprise Access Control

Enterprise Access Control (EACL) is a novel Datalog-based access control system with a declarative API that exploits the mutually-exclusive binary nature of allow vs. deny rules to efficiently maintain sparse bitfield matrices to answer ACL queries.

## Why EACL?

 - Data becomes a liability over time.
 - Authentication is a solved problem; authorization is not.
 - Access control is always an afterthought and it's painful to add later.
 - Big businesses frequently get audited during mergers &amp; acquisitions, i.e. compliance.
 - There is typically no central dashboard for auditing data privacy across divisions.
 - You need to understand the shape of data to solve the problem.
 - Checking access at row or entity-level can easily suffer from N+1 problem. 

## Design Goals

 - Easy to add to existing projects
 - Drop-in replacement for role-based middleware (understand sessions)
 - Flexible rules
 - Composable rules, "Can I do `this` + `that`?"
 - Support for Roles
 - Heritable roles
 - Fast enough for enterprise (10M+ entities, ~30k employees)
 - Built-in audit logs, e.g. "Who had access to the AWS root keys on 5 March 2020? Who accessed them?"
 - Incremental matrix maintenance for fast updates 
 - Support common relational DB rule mappings.
 - Hostable as an Authorization Service (w/aggressive client-side caching)

## The Shape of a Rule

All rules have the same shape, with each parameter being optional:

 - `:eacl/who` means the principal agent, e.g. user(s), role(s), sensor(s) or key card(s).
 - `:eacl/what` means resource(s), e.g. "Invoices" or "Invoice Nr. 567".
 - `:eacl/why` is typically used for auditing why this resource was accessed, e.g. “July 2020 Stock Take”.
 - `:eacl/when` means when the date or time span on which this rule applies. Omitting 'when' defaults to 'right now'. Useful for historical audits and time travel.
 - `:eacl/where` means a physical or logical region or location, e.g. "Cape Town" or "Houston".
 - `:eacl/how` (Action) - Optional. Typically CRUD, one of: :create, :read, :update, :delete, but can also be :audit.

## API Design

EACL features a declarative API with only a few functions that all take a `rule` and one of a DB-snapshot (for querying) or connection (for transacting):

 - `(can? db rule)` 
 - `(grant! conn rule)`
 - `(deny! conn rule)`

Additionally, the API supports enumeration for "filling in the blanks":

 - `(who-can? db rule)` expects `:eacl/who` and returns matching rules for this principle.
 - `(when-can? db rule)` returns a time series of valid timespans for this rule.
 - `(what-can? db rule)` expects `:eacl/who` and returns sequence of `:eacl/what` rules.
 - `(how-can? db rule)` returns a sequence of matching `:eacl/how` for this rule.
   
These enumerators let you ask questions like "Who can read or edit this resource at these locations next week?"

# Example

Fire Dept. Chief John (a consultant) needs access to the server room on Friday to conduct an annual fire safety inspection at the Houston branch. To limit liability, his access should be short-lived and constrained to areas of interest (DC1 and DC2). Here is how you model the ACL check for John's keycard in Clojure:

    {:eacl/who   [:eacl/email "john@example.com"]
     :eacl/what  :server-room-door
     :eacl/where #{[:eacl/ident :branch/houston.dc1] [:eacl/ident :branch/houston.dc2]}
     :eacl/how   #{:open :close}
     :eacl/when  #inst "2020-07-15"  ;; (time ranges not supported yet)
     :eacl/why   :audits/2020-Fire-Safety-Audit]})

Now, we can check if any rules satisfy this demand by calling `eacl/can?` with the current DB and the rule above:

    (eacl/can? db
      {:eacl/who   [:eacl/email "john@example.com"]
       :eacl/what  :server-room-door
       :eacl/where [:eacl/ident :branch/houston.dc1]
       :eacl/how   #{:open :close}
       :eacl/when  #inst "2020-07-15"  ;; (time ranges not supported yet)
       :eacl/why   :audits/2020-Fire-Safety-Audit]})
    => false
    
EACL says no, so the pod bay doors won't open. Let's grant John access to the doors by calling `eacl/grant!`

    (eacl/grant! conn
      {:eacl/who   [:eacl/email "john@example.com"]
       :eacl/what  :server-room-door
       :eacl/where #{[:eacl/ident :branch/houston.dc1] [:eacl/ident :branch/houston.dc2]}
       :eacl/how   #{:open :close}
       :eacl/when  #inst "2020-07-15"  ;; (time ranges not supported yet)
       :eacl/why   :audits/2020-Fire-Safety-Audit]})
        
Now, the same call to `eacl/can?` returns `true`. And to revoke the rule, we just call `(eacl/deny! conn ...)` with the same argument.

What's nice about this access control design is:

 - It's flexible enough to secure for both digital and physical assets
 - Consistent declarative shape. The same rule.

## Roles & Inheritance

Rules nest. If John belongs to the Managers group and you grant Managers the same rights, then John inherits those rights, except for any rules lower down that are specifically denied to John. 
  
## Supported Platforms

EACL would like to support all the main web programming frameworks, but right now is built for Clojure + Datahike.

If I can find some funding, I am happy to keep working on it and spend some time speeding it up.  

## State of the Project: (Early Alpha)

The core of the engine is ~400 lines and currently has a slow implementation backed by Datahike. It is actively used for access control in the [Bridge](https://www.tradebridge.app/) eCommerce implementation.

## Order of Execution

`deny!` trumps `allow!`, unless there exists a more-specific `allow!` for on the same resource (:eacl/what) for a given who (:each/who). 

## Licence

Please refer to the [Business Source Licence](LICENCE). You can do what you want but if you want to build an authorization service or build out replication, you need to licence EACL. Contact petrus@enterpriseacl.com for pricing.  
