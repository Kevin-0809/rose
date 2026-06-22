# Message Flow Log Entry Page Design

## Goal

Add a browser page for manually inserting request and optional response messages into `msg_flow_log_request` and `msg_flow_log_response`, then redirect users to the existing message query page for immediate verification.

## Scope

This feature adds one UI workflow:

- Open a new message entry page from the top navigation.
- Submit one form that always inserts one request row.
- Insert one response row only when response content or response metadata is provided.
- Redirect to `/messages/flow-logs?query=<transId>` after a successful save.

The feature does not add editing, deletion, bulk import, file upload, or automatic timestamp parsing.

## User Experience

The new page lives at `/messages/flow-logs/new` and uses the existing application layout and restrained form style.

The form has three groups:

- Common fields: source IP, transaction ID, transaction code, message type.
- Request fields: transaction time, global sequence number, teller number, request message.
- Optional response fields: response time, return code, return message, response message.

Required inputs are:

- `sourceIp`
- `transId`
- `txnCode`
- `txnTime`
- `requestMessage`

The time fields are submitted as existing database-compatible millisecond timestamps (`bigint`). This avoids ambiguous conversion between display date formats and stored values.

When validation fails, the same page is re-rendered with the submitted values and a concise error message. When saving succeeds, the controller redirects to the query page using the transaction ID so the inserted rows are visible immediately.

## Backend Design

Add a form record, tentatively `MessageFlowLogEntryForm`, under `com.spdb.message`. It represents submitted form values and centralizes checks such as required fields and whether the optional response row should be inserted.

Extend `MessageFlowLogService` with a save method:

- Validate and normalize string fields by trimming them.
- Insert the request row into `msg_flow_log_request`.
- Convert `requestMessage` to UTF-8 bytes for the `bytea` column.
- Insert the response row into `msg_flow_log_response` only when at least one response-specific field is present.
- Convert `responseMessage` to UTF-8 bytes when present; otherwise store `null`.

The service will continue using `NamedParameterJdbcTemplate`, matching the existing query implementation.

## Controller Design

Extend `MessageFlowLogController`:

- `GET /messages/flow-logs/new` renders the form with an empty `MessageFlowLogEntryForm`.
- `POST /messages/flow-logs/new` validates and saves the form.
- On validation errors, render the same template with an error message and submitted values.
- On success, redirect to `/messages/flow-logs?query=<encoded transId>`.

The active navigation key for the new page is `message-flow-log-entry`.

## Template And Navigation

Create `src/main/resources/templates/messages/flow-log-entry.html`.

Update `src/main/resources/templates/fragments/layout.html` to add a flat navigation link labeled `报文录入` pointing to `/messages/flow-logs/new`.

The page should avoid instructional marketing copy. Labels and section titles should be direct and operational.

## Error Handling

Validation errors are handled before insert:

- Missing required text field.
- Missing request timestamp.
- Non-numeric timestamp values are rejected by Spring binding and surfaced on the form.

Database exceptions are not hidden; they should follow the application default error behavior. This keeps the first implementation small and consistent with the rest of the app.

## Testing

Use TDD for implementation.

Service tests:

- Saving a full form inserts one request row and one response row with UTF-8 byte content.
- Saving a form without response fields inserts only the request row.

Controller tests:

- GET renders the entry page model.
- POST valid form redirects to the query page.
- POST invalid form returns the entry page with an error message.

Template tests:

- The new template contains the expected form action, required field names, and response fields.
- The layout contains the new navigation link.

Full verification command: `mvn test`.
