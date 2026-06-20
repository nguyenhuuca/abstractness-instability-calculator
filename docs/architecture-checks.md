# Architecture Checks

Enforce architecture rules from a YAML spec — a project that breaks them fails the build. A
**component** is a layer (controller/service/repository) or a functional module, matched by
fully-qualified class-name regex, so one mechanism covers both granularities.

```bash
java -jar core/target/aic-cli.jar --scan=<proj> --arch=layered          # built-in template
java -jar core/target/aic-cli.jar --scan=<proj> --arch=./my-arch.yaml    # your own rules
```

In the **web UI**, pick a template from the *Architecture* dropdown next to the path field; the result
shows as a banner (green when compliant, red listing violations).

!!! tip "Or let the project own its rules"
    A project can enable the architecture check (with a template or an inline spec) in its
    [`aic-check.yaml`](configuration.md) — then no `--arch` flag or dropdown selection is needed.

## Built-in templates

| Template | Idea |
|----------|------|
| `layered` | Web → Service → Repository → Domain |
| `hexagonal` | Ports & Adapters: Adapter → Application → Domain |
| `onion` | Onion / Clean: Infrastructure → Application → Domain |

## Spec schema

```yaml
name: Layered
components:
  - name: Web
    matches: ['.*\.controller(\..*)?$', '.*\.web(\..*)?$']
  - name: Service
    matches: ['.*\.service(\..*)?$']
  - name: Repository
    matches: ['.*\.repository(\..*)?$', '.*\.repo(\..*)?$']
  - name: Domain
    matches: ['.*\.domain(\..*)?$', '.*\.model(\..*)?$']
access:                       # allow-list: a component may depend ONLY on those listed
  Web:        [Service, Domain]
  Service:    [Repository, Domain]
  Repository: [Domain]
  Domain:     []
forbidden:                    # explicit deny edges (independent of access)
  - from: Domain
    to: Web
naming:                       # required class simple-name regex per component
  Repository: '.*Repository$'
options:
  ignoreUnmatched: true
  forbidCycles: true
```

### Rules

- **access** — an allow-list per component; depending on anything not listed is a violation.
- **forbidden** — explicit denied edges, independent of `access`.
- **naming** — class simple names in a component must match the regex.
- **forbidCycles** — no dependency cycle between components.

### Defaults when omitted

| Spec field | Default |
|------------|---------|
| `access` | no allow-list (subject only to `forbidden`) |
| `forbidden` | none |
| `naming` | none |
| `options.ignoreUnmatched` | `true` (classes matching no component are skipped). Set to `false` to flag any first-party class that matches no component as an `unmatched` violation. |
| `options.forbidCycles` | `false` |

## Output

The result appears under `architecture` in the [JSON envelope](json-export.md) (`specName`,
`compliant`, `violations[]`), and any violation makes the CLI exit `1`.
