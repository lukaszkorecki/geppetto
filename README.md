# Geppetto

> [!WARNING]
> Name, configuration format, and some other details are subject to change.
> Expect breakage until this warning goes away.

Ever wanted something like Docker Compose, but without Docker? [Foreman](https://github.com/ddollar/foreman) is kinda there, but lacks a lot of useful features. [Supervisord](https://supervisord.org/) does way too much. Now there's a simple way.

## What is Geppetto?

Geppetto uses a YAML-based configuration file defining services and their dependencies, then runs them in parallel while respecting the dependency graph. Each service's output is color-coded and prefixed with the service name and process ID, making it easy to track multiple concurrent processes.

Think of it as a simple process orchestrator for development environments, integration testing, or managing multiple services locally.

## Features

- **Parallel Execution**: Runs services concurrently while respecting dependencies
- **Dependency Management**: Services can depend on other services; dependent services only start after their dependencies are running
- **Color-Coded Output**: Each service gets a unique color for easy visual identification
- **Environment Variables**: Set custom environment variables per service or load from files
- **Environment Variable Interpolation**: Use `${VAR}` and `${VAR:-default}` syntax in config values
- **Tag-Based Filtering**: Run only services with specific tags
- **Process Lifecycle Management**: Proper cleanup and signal handling
- **Native Binary**: Compiles to a native executable using GraalVM for fast startup and zero-dependency installations
- **Configuration Validation**: Schema validation ensures your config is correct before execution

## Installation

### From GitHub Releases

Download the latest binary from [GitHub Releases](https://github.com/lukaszkorecki/geppetto/releases):

```bash
# macOS ARM64
curl -L https://github.com/lukaszkorecki/geppetto/releases/latest/download/geppetto-macos-arm64 -o geppetto
chmod +x geppetto
```

### Building from Source

Requirements:
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- [GraalVM](https://www.graalvm.org/) with `native-image` installed

For fastest setup, [Mise](https://mise.jdx.dev/) config is provided.

```bash
# assuming mise is activated
./build.sh
```

This creates a native `geppetto` binary in `./bin/`.

## Hacking

Geppetto is written in Clojure, so it's easy to work on and extend. You can start it by running

```
clojure -M:run <flags>
```

## Usage

```bash
geppetto [options] <config-file.yaml>
```

### CLI Options

```
-e, --exit-mode MODE   Exit behavior when services complete or fail (default: all)
                       - all: wait for ALL services to complete
                       - any: exit when ANY service completes (successful or failed)
                       - on-failure: exit immediately when ANY service fails (non-zero exit)
-s, --services SERVICES  Comma separated list of services to run (default: all)
-t, --tags TAGS        Comma separated list of tags to filter services (default: all)
-r, --root ROOT        Root directory for resolving relative service paths (default: config file directory)
-p, --print-services   Print the list of services defined in config and exit
-v, --version          Show version
-h, --help             Show help
```

### Configuration Format

Create a YAML file defining your services:

```yaml
settings:
  root_dir: ~/projects  # Optional: base directory for relative paths (default: config file location)
  exit_mode: on-failure # Optional: all, any, or on-failure (default: all, CLI --exit-mode takes precedence)

services:
  - name: database
    command: docker compose up postgres
    env:
      POSTGRES_PASSWORD: secret

  - name: backend
    command: clj -M:start
    dir: ./backend
    depends_on:
      - database
    env:
      DATABASE_URL: postgresql://localhost:5432/mydb

  - name: nginx
    command: nginx ./dev.conf

  - name: frontend
    command: npm run start
    dir: ./frontend/
    depends_on:
      - backend
    env:
      API_URL: http://localhost:3000
    tags:
      - web
      - dev
```

### Service Configuration

Each service supports the following properties:

| Property | Required | Description |
|----------|----------|-------------|
| `name` | Yes | Unique identifier for the service |
| `command` | Yes | Shell command to execute |
| `dir` | No | Working directory for the command |
| `depends_on` | No | List of service names this service depends on |
| `env` | No | Map of environment variables |
| `tags` | No | List of tags for filtering |
| `parse_json_logs` | No | Parse JSON log lines and output as readable YAML |

### Root Directory Resolution

Relative paths in service `dir` are resolved against a root directory. The resolution priority is:

1. `--root` CLI flag (highest priority)
2. `settings.root_dir` in config file
3. Config file's parent directory (default)

This allows separating config files from project directories:
```bash
geppetto ~/configs/services.yaml --root ~/projects
```

### Special Environment Variables

Geppetto automatically sets the following environment variables for each service:

- **`geppetto.service-name`**: The name of the current service

### Environment Variable Interpolation

Config values can reference environment variables using shell-like syntax:

```yaml
services:
  - name: backend
    command: ./start.sh
    dir: ${PROJECT_ROOT}/backend
    env:
      DATABASE_URL: postgresql://${DB_HOST:-localhost}:5432/mydb
      API_KEY: ${API_KEY}
```

Supported syntax:
- `${VAR}` - replaced with the value of `VAR`, or empty string if not set (with a warning)
- `${VAR:-default}` - replaced with the value of `VAR`, or `default` if not set

This works in all string values including `command`, `dir`, and `env` values.

## How It Works

Geppetto uses [Stuart Sierra's Component library](https://github.com/stuartsierra/component) to manage the service lifecycle and dependency graph. Each service is a component that:

1. Starts a process when initialized
2. Streams stdout/stderr with colored, prefixed output
3. Monitors process health
4. Properly cleans up on shutdown

The dependency system ensures services start in the correct order, with dependent services waiting for their dependencies to be running before starting.

## Platform Support

Currently builds native binaries for **macOS ARM64**. Linux support is planned.

## Roadmap

- [ ] Linux native binary builds
- [ ] Load environment from command output (`env_command`)
- [ ] Load environment variables from file (like Docker's `--env-file`)
- [ ] Service restart policies
- [ ] Health checks
- [ ] Better error handling and reporting
