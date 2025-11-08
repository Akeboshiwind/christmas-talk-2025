# christmas-talk

A simple full-stack application using Squint (ClojureScript) with Bun.

## Prerequisites

- [Bun](https://bun.sh) installed
- [Babashka](https://babashka.org) installed

## Setup

Install dependencies:

```bash
bun install
```

## Development

Start the development server with hot-reload:

```bash
bb dev
```

This runs Squint compilation, Bun bundling, and the server in parallel with watch mode enabled.

Server will be available at https://localhost:3000

## Build

Build for production:

```bash
bb build
```

Clean build artifacts:

```bash
bb clean
```

## Project Structure

```
├── src/
│   ├── backend/      # Backend Squint code
│   └── frontend/     # Frontend Squint code
├── build/            # Squint build output (gitignored)
└── target/           # Final bundled output (gitignored)
```
