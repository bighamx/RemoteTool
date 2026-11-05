# RemoteTool Android usage telemetry v1

Android client: RemoteTool. API, storage and administration UI: wxbackend.

## Endpoint

`POST https://api.xbyham.com/api/app-telemetry/v1/report`

Public, dedicated write-only endpoint. No shared API Key, account cookie, OAuth token, D1 token or admin credential in APK. Server must validate the fixed schema, restrict app/package pairs, limit request size and rate, and prevent arbitrary generic log writes. Reuse wxbackend's D1 logging infrastructure; dedicated installation/daily aggregate tables are suitable for atomic idempotent upserts without inventing a user/API key identity.

```json
{
  "schemaVersion": 1,
  "appId": "remotetool",
  "packageName": "com.chuckiehelper.mobile",
  "installationId": "3663d1dd-6789-4c04-808e-df2dbce655a5",
  "firstSeenUtc": "2026-11-04T15:00:00Z",
  "device": {
    "manufacturer": "Xiaomi",
    "brand": "Redmi",
    "model": "example-model",
    "androidVersion": "16",
    "sdkInt": 36
  },
  "appVersion": "0.3.149-dev.example",
  "versionCode": 1758,
  "days": [
    { "date": "2026-11-04", "openCount": 3 }
  ]
}
```

Second supported pair: `appId=remotetool-phonecontrol`, `packageName=com.chuckiehelper.phonecontrol`. Installation IDs are UUID v4 random per installation and per app, not hardware identifiers, not shared/cloud-synced. No conversation content, file paths, account IDs, serials, IMEI, advertising/Android IDs or user-entered device names.

`firstSeenUtc` is the time telemetry was first initialized, including upgrade from an older App. It is persisted across process restarts. Server keeps its own first-received UTC timestamp and must not overwrite that on repeat requests. Existing installations are counted once when they first use this telemetry-enabled version; historical installs before reporting cannot be inferred.

`days` contains at most 31 retained Hong Kong calendar dates (`Asia/Hong_Kong`, UTC+08:00), newest/oldest ordering irrelevant. `openCount` is the cumulative count for that installation/day, not a delta. Upsert on `(appId, installationId, date)` with **MAX(existing.openCount, incoming.openCount)**; never add snapshots. Thus retries, timeouts, duplicate or out-of-order reports cannot inflate opens or DAU/MAU. Clamp values / validate date range and types. Client retains recent 31 days, prunes older dates, bounds counts to 10000/day. `days: []` is allowed for installation-only backfill.

Success only after durable writes: HTTP 200/202 JSON `{"accepted":true}`. Client requires that explicit boolean before acknowledging the snapshot. Do not return accepted for merely queued or failed storage writes. Non-2xx, malformed JSON, or missing/false accepted keeps pending data for backoff retry. Errors should not expose internal credentials.

## User-facing behavior

- Setting `使用统计`, **default on**, local preference only; explain installation/model/app-version/activity scope.
- First visible App activity always schedules an installation report if no acknowledged installation record exists; applies equally to fresh installs and existing users upgrading to this version.
- Foreground entry after at least 30 seconds background counts one open; rotations, launching child activities, and quick external-app round trips do not inflate opens.
- First foreground observation after Hong Kong midnight records that day's activity even without a process restart.
- Off clears pending reporting data and cancels in-flight requests/retries. Already received server records remain; an in-flight request might already have arrived. Re-enabling keeps the same installation ID and resumes without duplicate install count.
- Network is asynchronous; persisted bounded snapshots survive process death. Retry with capped backoff; never block launch or show telemetry errors in chat.

## Administration statistics

Restrict read APIs/pages to existing appropriate log/admin permission, not the public ingestion endpoint. Show per-app cumulative reported installations, today's/new installations, opens, DAU, calendar-month MAU, rolling 30-day active separately, daily trend, model/manufacturer/Android/App version distribution. Deduplicate installations by `(appId, installationId)` and DAU/MAU by distinct installation ID, not report count; Hong Kong calendar boundaries. Do not add the two apps' installations as unique physical phones.

## Verification

App tests: default-on setting; legacy-install backfill; stable ID; disabled state; failed/malformed ack; out-of-order snapshot acknowledgements; offline/process restart; date rollover; foreground debounce. Backend/Worker: anonymous valid report accepted, invalid app/package/extra payload/oversize/rate rejected, reads still authenticated, duplicate and out-of-order counts remain MAX, distinct-day/month aggregation, durable ACK. Coordinate API shape changes with the Android client before deployment.

Activity clarification: presence of a day row means active, even when openCount is 0 (for example staying foreground across midnight or enabling telemetry while already open). DAU/MAU count distinct installations with any day row, not only openCount > 0. First real foreground opening increments openCount; a midnight heartbeat only creates a 0-count active day.
