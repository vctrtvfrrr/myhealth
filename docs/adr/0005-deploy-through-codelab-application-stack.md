# Deploy through the CodeLab Application Stack contract

The ingestion API is delivered as the `myhealthbridge` Application Stack through `codelab/deploy-stack`, using a commit-SHA image in the Gitea Registry, a repository-owned `compose.yml`, and `APPENV_*` runtime configuration. The first version intentionally publishes an empty observability generation because dashboards and application-specific alert rules are outside its scope; deployment artifacts conform to the existing VPS platform instead of introducing a parallel delivery mechanism.
