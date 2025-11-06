# christmas-talk

A simple full-stack application using Squint (ClojureScript) with Bun.

## Prerequisites

- [Bun](https://bun.sh) installed

## Setup

Install dependencies:

```bash
bun install
```

## Development

Start the dev server:

```bash
bun run dev
```

Server will be available at http://localhost:3000

## Build

Compile Squint code:

```bash
bun run build
```

Clean build artifacts:

```bash
bun run clean
```

## Adding Dependencies

1. Install the package:
```bash
bun add package-name
```

2. Add to the import map in `public/index.html`:
```json
{
  "imports": {
    "package-name/": "/node_modules/package-name/"
  }
}
```

3. Use in your Squint code:
```clojure
;; Import will be added by Squint compiler
```

## Project Structure

```
├── src/
│   ├── clj/          # Backend Squint code
│   └── cljs/         # Frontend Squint code
├── public/           # Static assets
└── target/           # Build output (gitignored)
```
