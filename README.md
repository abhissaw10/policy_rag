# Enterprise HR Policy RAG — Parts 1 and 2

Spring Boot and Vaadin application for validating, ingesting, semantically chunking, embedding, and indexing HR policy PDFs.

Part 2 adds a grounded policy chat at [http://localhost:8080/ask](http://localhost:8080/ask) and a JSON `POST /chat` endpoint.

## What is included

- `POST /upload` multipart endpoint with `employeeId`, `checksum`, and `file` parts.
- Browser-side SHA-256 calculation using Web Crypto. The UI sends the checksum to `/upload` and never trusts a server-generated checksum.
- Matching browser and backend validations: PDF only, maximum 25 KB, employee ID maximum 5 characters.
- Backend PDF signature and PDFBox parsing checks in addition to filename validation.
- Groq semantic chunking using `openai/gpt-oss-20b` by default.
- Groq selects sentence-unit boundary indices; Java reconstructs chunks from the original PDF text without model rewriting. Large semantic groups are split at sentence boundaries to a maximum of 350 words (unless a single sentence itself exceeds that size).
- Learned 384-dimensional BGE-small-en-v1.5 quantized semantic embeddings, running locally in Java through LangChain4j/ONNX. Model weights are bundled in the Maven dependency; no additional API key or embedding server is needed.
- Both uploads and retrieval use the same semantic model and normalization. Search questions receive BGE's retrieval instruction; policy text is embedded without that instruction. Reconstructed paragraphs also use this model for candidate ranking.
- A versioned Chroma collection with an embedding-model fingerprint prevents mixing old token-hash vectors with semantic vectors, even though both have 384 dimensions.
- ChromaDB HTTP integration and an ephemeral Chroma service in `compose.yml`.
- Unit and MVC tests for validation, PDF extraction, Groq request/response handling, lossless chunk reconstruction, ingestion orchestration, and API responses.

## Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 24+ (Vaadin can download it automatically during development)
- Docker (for local ChromaDB)
- A Groq API key

## Run locally

```bash
docker compose up -d chroma
export GROQ_API_KEY="your-groq-key"
mvn spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080).

The Chroma container has no mounted volume and `IS_PERSISTENT=FALSE`, so its contents are intentionally ephemeral for Part 1.

## Call the API directly

```bash
checksum=$(shasum -a 256 policy.pdf | awk '{print $1}')
curl -X POST http://localhost:8080/upload \
  -F employeeId=E1234 \
  -F checksum="$checksum" \
  -F file=@policy.pdf
```

Successful response:

```json
{"message":"File Uploaded Successfully","chunksStored":3}
```

Validation errors have a stable code and a user-facing message:

```json
{"code":"CHECKSUM_MISMATCH","message":"File checksum validation failed"}
```

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `GROQ_API_KEY` | none | Required for semantic chunking |
| `GROQ_MODEL` | `openai/gpt-oss-20b` | Groq chat model |
| `CHROMA_URL` | `http://localhost:8000` | Chroma server URL |
| `CHROMA_TENANT` | `default_tenant` | Chroma tenant |
| `CHROMA_DATABASE` | `default_database` | Chroma database |
| `CHROMA_COLLECTION` | `hr_policies_bge_small_en_v15_q_v1` | Collection containing only compatible semantic embeddings |

## Test

```bash
mvn test
```

Tests do not call Groq or require ChromaDB. Semantic retrieval tests run the actual local embedding model. The first Maven build downloads the model and ONNX dependencies; the tokenizer may also initialize its native-library cache on first use.

## Reindex policies uploaded before semantic embeddings

Old `hr_policies` vectors are incompatible with the semantic model. Stop the old application, keep Chroma running, and copy its stored policy text into the new collection with:

```bash
export CHROMA_COLLECTION=hr_policies_bge_small_en_v15_q_v1
mvn spring-boot:run -Dspring-boot.run.arguments="--app.chroma.reindex-from=hr_policies"
```

Reindexing computes new semantic vectors locally, retains document/chunk IDs, filenames and uploader attribution, and leaves the source collection untouched. It uses upsert so an interrupted copy can be rerun safely. No PDF re-upload, semantic chunking call or Groq key is needed for the copy itself. Reindex before serving chat traffic; restart normally without the reindex argument after it completes. If the ephemeral source collection is already gone, upload the PDFs again instead.

Changing only the embedding code is insufficient: old stored vectors must be re-created. A custom `CHROMA_COLLECTION` that points to the old, unlabelled collection is rejected for both uploads and search. Use a different destination collection; do not relabel old vectors as semantic embeddings.

## Groq errors

Groq can return HTTP 400 with `json_validate_failed` when generated output fails its JSON validation. The chunker requests only boundary indices to keep responses small. If that error occurs, it makes one further attempt in text mode, then validates the JSON and indices locally before any content reaches Chroma. Invalid or truncated responses are rejected; policy content is never silently omitted.

The backend logs the HTTP status, error code/type, parameter, and request ID when available. It does not log the API key, policy text, or failed model generation. Other HTTP 400 errors include the code and parameter in the UI message and are not retried. Restart the Spring Boot process after code/configuration changes before retrying an upload.

## Production notes

Part 1 intentionally uses ephemeral Chroma storage. Before production deployment, use persistent/external Chroma, domain-specific retrieval evaluations, authentication/authorization, malware scanning, rate limits, audit logging, encrypted secret management, and asynchronous ingestion for larger policy files. Prefer short, coherent source passages: the local model processes long input in token windows and combines their embeddings, which can dilute a section containing several unrelated rules.

## Policy chat

Restart the application after updating, then open `/ask` or follow **Ask HR** on the upload screen. Enter your employee ID to identify the requester. Policies uploaded by any employee are searchable by every employee; the requester does not need to match the uploader. Questions are independent: chat history is displayed in the UI, but is not added to later prompts.

```bash
curl -i -c /tmp/policy-chat.cookies -b /tmp/policy-chat.cookies \
  -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"employeeId":"E1234","prompt":"How many annual leave days do I receive?"}'
```

```json
{
  "status": "ANSWER",
  "message": "Employees receive 20 days of annual leave each year.\nSource: leave-policy.pdf",
  "filename": "leave-policy.pdf",
  "cached": false,
  "sessionId": "server-http-session-id",
  "traceId": "32-hex-characters",
  "llmCalls": 1
}
```

The sample answer is synthetic; actual answers depend on indexed documents. The `employeeid` spelling is also accepted.

### Retrieval and response rules

- The prompt is limited to 100 Unicode code points; employee IDs remain required and limited to five characters.
- Queries use the same learned BGE-small-en-v1.5 model as ingestion through `EmbeddingService.embedQuery`; document chunks and candidate paragraphs use `embedDocument`. Chroma performs cosine similarity search across the shared semantic policy collection. Uploader employee ID remains stored as attribution metadata, not an access filter. Chat traces include the embedding model fingerprint.
- Matching document chunks are reconstructed in order before paragraph extraction. Missing chunks cause a refusal instead of a partial quote.
- Section headings are excluded as standalone answers. Their body paragraphs are kept together so an exception in the next paragraph is not separated from its rule. Up to sixteen body candidates are ranked using the existing embedding service before LLM reranking.
- Groq reranks candidates by direct answer relevance and returns ranked paragraph IDs. A second, separate LLM call checks the proposed answer for support, question coverage, scope and preservation of relevant conditions/negations.
- If the complete paragraph plus citation fits, Java returns it verbatim. Otherwise Groq produces a focused summary with exact evidence quotes that Java validates against the body; ordinary model-generated summaries are independently checked before being returned.
- If a capped Groq summary is incomplete or exceeds the character budget, Java deterministically extracts the directly relevant source sentences and conditions. Because this fallback contains only source-derived clauses from the LLM-reranked paragraph, it does not make another verifier call.
- Summaries are labeled `Summary:`. The total visible message, including this label and the stored PDF filename citation, cannot exceed 250 Unicode code points. Neither the answer nor the source is arbitrarily truncated to fit. Failed verification produces a refusal and is not cached.
- New PDF uploads preserve PDFBox paragraph separators. Older indexed PDFs without reliable paragraph breaks may need re-uploading. The system treats an undelimited block as one paragraph rather than guessing fragments.
- A quoted answer normally uses two Groq calls (reranking and verification); a model-generated summarized answer normally uses three (reranking, summary, verification). A deterministic summary fallback uses two (reranking and the capped summary attempt). Limited JSON-generation recovery is permitted, with a shared hard ceiling of five calls.
- Local checks require exact supporting excerpts and retention of explicit statutory/contractual conditions from the selected body. An over-limit or condition-dropping summary can be repaired once within the same five-call budget.

### Validation and cache

The UI and backend enforce input limits. Backend guards normalize Unicode and reject control/hidden characters, bulk-policy requests, common instruction/role overrides, encoded-instruction requests, secrets and system-detail requests. Retrieved evidence is also checked for instruction/secret patterns. The LLM classifies other vague or unrelated questions and must find a directly answering paragraph. Invalid model output, invented IDs and truncated completions never become answers.

These are layered defenses, not a claim that a keyword detector recognizes every possible attack. The response path can emit only verified source text or fixed refusal messages; the LLM has no executable tools or system-information access.

Responses are cached in memory by normalized prompt and shared index revision, with a 10-minute TTL and a 1,000-entry LRU bound. The same question can reuse its answer across employees, while every request retains its own employee/session/trace information. Repeated valid questions return `cached: true` with zero LLM calls. Any successful upload invalidates the entire answer cache; an answer already in flight against an older revision is not cached. External Chroma edits are reflected after cache expiry.

`ANSWER`, `NOT_FOUND`, `NO_POLICIES` and `LIMIT_EXCEEDED` return HTTP 200; `INVALID`, `BLOCKED` and `OUT_OF_SCOPE` return HTTP 400; service failures return `UNAVAILABLE` with HTTP 503. Rejected prompts and service failures are not cached.

If the shared collection has not been populated, `NO_POLICIES` explains that a policy must be uploaded. This result is not cached and does not call Groq. It is distinct from `NOT_FOUND`, where policies are available but do not answer the question. Response events include `retrievedChunks` and `candidateParagraphs` counts and, when applicable, an `evidenceReason` to diagnose retrieval problems without logging policy bodies.

Employee ID currently identifies the requester/uploader; it is not authentication. The HR policy library is shared across employees by design.

## Chat observability and evals

Every processed chat request emits separate `chat.request` and `chat.response` Spring application events. Each component emits `tool.started` and `tool.completed` events with UTC timestamps, session ID, trace ID, span ID, parent span ID, tool name, status, and duration in milliseconds. Responses include cache status and the total LLM call count. HTTP responses also expose `X-Trace-Id`.

Events are logged to the console and appended as JSON Lines to `logs/chat-events.jsonl`. Override the path with `APP_OBSERVABILITY_EVENTS_FILE`. The request and response events contain the actual messages for external evaluation; tool events contain no API keys or retrieved policy bodies.

```bash
# Inspect request/response pairs for an external evaluator.
jq -c 'select(.eventType == "chat.request" or .eventType == "chat.response")' logs/chat-events.jsonl

# Default tests are offline.
mvn test

# Live evals use actual Chroma, the Part 1 embedding service and the configured Groq LLM.
# Start Chroma and set GROQ_API_KEY first.
RUN_LIVE_EVALS=true mvn -Dtest=PolicyChatLiveEvalTest test
```

Live evals cover an answer present in the policies, an HR question whose answer is absent, a question outside the policy documents, and a maternity-leave split question with a standalone heading and an over-limit policy body. They assert exact quotation/citation or a verified summary, retention of the statutory qualification, response length, LLM budget and repeat-prompt cache behavior. Each run creates a uniquely named synthetic Chroma collection and deletes only that collection afterward. Evaluation events are exported to `target/evals/chat-events.jsonl`. They can be joined by trace ID and graded elsewhere; no external grading system is implemented.

Response events expose `rerankedParagraphIds`, `verification` (`PASSED`/`REJECTED`/`DETERMINISTIC_SOURCE_EXTRACTION`), and `answerMode` (`QUOTE`/`SUMMARY`) when applicable. Tool spans distinguish `groq.paragraph.rerank`, `groq.answer.summarize` and `groq.answer.verify`. The verifier uses the same Groq model in a separate call for ordinary model-generated answers; deterministic source extraction is identified explicitly in the trace.
