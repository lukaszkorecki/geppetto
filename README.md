# Geppetto

Ever wanted something like Docker Compose, but without Docker? [Foreman](https://github.com/ddollar/foreman) is kinda there, but lacks a lot of useful features. [Supervisord](https://supervisord.org/) does way too much. Now there's a simple way.

## What is Geppetto?

Geppetto uses a YAML-based configuration file defining tasks and their dependencies, then runs them in parallel while respecting the dependency graph. Each task's output is color-coded and prefixed with the task name and process ID, making it easy to track multiple concurrent processes.

Think of it as a simple process orchestrator for development environments, integration testing, or managing multiple services locally.

## Features

- **Parallel Execution**: Runs tasks concurrently while respecting dependencies
- **Dependency Management**: Tasks can depend on other tasks; dependent tasks only start after their dependencies are running
- **Color-Coded Output**: Each task gets a unique color for easy visual identification
- **Environment Variables**: Set custom environment variables per task
- **Process Lifecycle Management**: Proper cleanup and signal handling
- **Native Binary**: Compiles to a native executable using GraalVM for fast startup and zero-dependency installations
- **Configuration Validation**: Schema validation ensures your config is correct before execution

## Installation


**TODO**: get the binary from GH releases

### Building from Source

Requirements:
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- [GraalVM](https://www.graalvm.org/) with `native-image` installed

For fastest setup, [Mise](https://mise.jdx.dev/) config is provided.

```bash
# assuming mise is actiaveted
./build.sh
```

This creates a native `geppetto` binary in the current directory.

## Hacking

Geppetto is written in Clojure, so it's easy to work on and extend. You can start it by running

```
clojure -M:start <flags>
```



## Usage

```bash
./geppetto <config-file.yaml>
```

### Configuration Format

Create a YAML file defining your tasks:

```yaml
tasks:
  - name: database
    command: docker compose up postgres
    env:
      POSTGRES_PASSWORD: secret

  - name: backend
    command: clj -M:start
    cwd: ./backend
    deps:
      - database
    env:
      DATABASE_URL: postgresql://localhost:5432/mydb

  - name: nginx
    command: nginx ./dev.conf

  - name: frontend
    command: npm run start
    cwd: ./frontend/
    deps:
      - backend
    env:
      API_URL: http://localhost:3000
    tags:
      - web
      - dev
```

### Task Configuration

Each task supports the following properties:

- **`name`** (required): Unique identifier for the task
- **`command`** (required): Shell command to execute
- **`cwd`** (optional): Working directory for the command
- **`deps`** (optional): List of task names this task depends on
- **`env`** (optional): Map of environment variables (can be empty `{}`)
- **`tags`** (optional): List of tags for organization/filtering

### Special Environment Variables

Geppetto automatically sets the following environment variables for each task:

- **`GP_ID`**: The name of the current task


## How It Works

Geppetto uses [Stuart Sierra's Component library](https://github.com/stuartsierra/component) to manage the task lifecycle and dependency graph. Each task is a component that:

1. Starts a process when initialized
2. Streams stdout/stderr with colored, prefixed output
3. Monitors process health
4. Properly cleans up on shutdown

The dependency system ensures tasks start in the correct order, with dependent tasks waiting for their dependencies to be running before starting.

## Platform Support

Currently builds native binaries for **macOS**. Linux support is planned.

## Roadmap

- [ ] Linux native binary builds
- [ ] Tag-based filtering (run only tasks with specific tags)
- [ ] CLI improvements (better argument parsing)
- [ ] Environment variable interpolation
- [ ] Load environment from command output (`env-from`)
- [ ] Task restart policies
- [ ] Health checks
- [ ] Better error handling and reporting
