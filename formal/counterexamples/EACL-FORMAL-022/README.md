# EACL-FORMAL-022 — typed-limit shadowing omitted the numeric limit

The legacy recursive evaluator reports both the exceeded counter and its
configured numeric limit. The generated indexed evaluator reported the same
typed error and counter but dropped the limit while adapting the generated
outcome back to public `ExceptionInfo`.

The verification shadow then concealed the difference: its error projection
kept only keyword-valued fields. The existing cross-backend shadow regression
therefore passed even though generated authority changed public error data.

The generated host adapter now maps each verified limit kind back to the exact
validated limit supplied to the generated state machine and avoids adding a
generated-only direction field to the legacy public error shape. Shadow
comparison also retains the bounded numeric limit fields for internal
equality. The reporter still receives no authorization value or request
material—only changed field names, safe result variants, and an error type.
