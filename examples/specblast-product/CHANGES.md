# SpecBlast output: before and after the verification gate

`before/` is the project SpecBlast generated for a product query API, as committed to
[roseweigee/product](https://github.com/roseweigee/product) at commit `25bb253`.
`after/` is the same project with every ERROR fixed.

```bash
./run.sh verify-project examples/specblast-product/before   # BLOCKED, 7 errors, 12 warnings, exit code 1
./run.sh verify-project examples/specblast-product/after    # READY_FOR_HUMAN_APPROVAL, 0 errors, 2 warnings
```

| Rule | Finding in `before/` | Fix in `after/` |
| --- | --- | --- |
| J-01 | `ProductServiceImpl` copied the database exception message into the API response (CWE-209) | Removed the catch; the global handler logs the cause and returns a fixed message |
| J-06 | `catch (Exception e)` in the service | Same change |
| J-05 | Both exception handlers answered errors with HTTP 200 | Business errors return 400, system errors 500; body format unchanged |
| K-03 | SIT, UAT and prod deployed `:latest` | Manifests use `IMAGE_TAG`; the pipeline replaces it with the commit SHA |
| B-04 | UAT and prod pulled `product/product`, which the pipeline never builds (it pushes `specblast/product`) | All environments use `specblast/product` |
| K-04 | Non-root not enforced by Kubernetes | `runAsNonRoot: true`, `runAsUser: 10001`, `allowPrivilegeEscalation: false` |
| K-04 | (hidden trap) `runAsNonRoot` fails at startup when the image user is a name | Dockerfile creates the user with UID 10001 and runs `USER 10001` |
| B-01 | Cloud Build deployed to SIT without running tests or any verification | New steps: `mvn verify` with SonarQube, then the verification gate; either failing stops the build |
| B-03 | `-DskipTests` with no test step anywhere | Resolved by B-01 (tests now run in their own step) |
| Q-01 | Coverage was neither measured nor enforced | JaCoCo in `pom.xml` with a 70% line-coverage check in `mvn verify`; `lombok.config` marks Lombok code as generated so it is not counted |
| Q-02 | No static analysis before deployment | The first Cloud Build step runs `mvn verify sonar:sonar` with `sonar.qualitygate.wait=true`; the token comes from Secret Manager |

SpecBlast's generated Mockito tests already met the test rules (T-01: a test class for every
controller and service; T-02: a failure-path test). Tests were updated to the new behavior, and one test was added: a database failure returns
HTTP 500 with the fixed message, so internal details such as a JDBC URL never reach the client.

## Left for the human approver (WARN)

| Rule | Why it is acceptable here |
| --- | --- |
| K-03 dev | `product:latest` is built locally by skaffold for development only |
| K-07 | `k8s/secret.yaml` holds placeholders only; moving it to Secret Manager is the next step |

## Not verified in this environment

`mvn verify` (gate P6) was not run, because Maven could not download Spring Boot dependencies
in the sandbox where this was built. The edited Java files were syntax-checked only.
Run `./run.sh verify-project examples/specblast-product/after --build` on a machine with Maven access.
The 70% coverage minimum is a starting value: adjust it after the first real run. The SonarQube step
needs a SonarCloud (or SonarQube server) project and a `sonar-token` secret in Secret Manager.

## Feeding this back into SpecBlast

These fixes belong in SpecBlast's generator, not in each generated project: the prompt and the
hard-coded templates (Dockerfile, deployment.yaml, cloudbuild.yaml) should produce the `after/`
shape directly. The gate then confirms every new project, instead of a person finding the same
problems again.
