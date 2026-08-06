# EACL-FORMAL-031 — CI could not execute the artifact gate

The formal job verified all 21 Dafny projects and rebuilt Java, JavaScript, and
the browser bundle, then failed before measuring them:

```text
/usr/bin/env: ‘bb’: No such file or directory
```

`bin/check-generated-artifact-size` has a Babashka shebang. The local run had
Babashka 1.12.213, but the GitHub Actions job did not install any Babashka
runtime. This is not a failed size threshold and not a production engine bug;
it is a fail-closed assurance-pipeline availability defect.

The workflow now installs the exact local Babashka version. The retained
regression also checks that the browser bundle is rebuilt before the artifact
measurement is invoked, so adding the interpreter cannot turn the gate into a
stale-artifact check.
