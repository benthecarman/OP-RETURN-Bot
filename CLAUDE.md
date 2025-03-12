# OP-RETURN-Bot Development Guide

## Build Commands
- `sbt run` - Start application on http://localhost:9000/
- `sbt test` - Run all tests
- `sbt "testOnly *TestName"` - Run specific test class
- `sbt "testOnly package.TestName -- -z \"specific test\""` - Run specific test method
- `sbt scalafmtAll` - Format code
- `sbt scalafmtCheckAll` - Check formatting without changes
- `sbt downloadBitcoind downloadLnd` - Download needed binaries

## Code Style
- **Formatting**: 80 character line limit, 2-space indentation (call sites), 4-space indentation (definitions)
- **Naming**: CamelCase for classes/objects, camelCase for methods/variables
- **Types**: Explicit type definitions for parameters and returns
- **Imports**: Auto-sorted by scalafmt
- **Error Handling**: Use `Try`, `Option`, and `Future` for functional error handling
- **Async**: Prefer `Future` for asynchronous operations
- **Testing**: ScalaTest with FunSpec-style and "it should" format

## Project Structure
- Play Framework MVC: controllers/, models/, views/, config/
- Test organization: unit/, functional/, controllers/