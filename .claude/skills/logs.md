---
name: logs
description: Tail or search logs for a specific NEXUS service container
---

Retrieve logs from a NEXUS service container.

Usage examples:
- `/logs nexus-fraud-service` — tail last 50 lines
- `/logs nexus-fraud-service 200` — tail last 200 lines
- `/logs nexus-fraud-service error` — search for ERROR entries
- `/logs nexus-fraud-service follow` — live follow (press Ctrl+C to stop)

Steps:
1. Extract the service name and optional argument from the user's request.

2. Verify the container is running:
```bash
docker ps --filter name=<service-name> --format "{{.Names}} {{.Status}}"
```

3. Based on the argument:
   - Default (no arg): `docker logs --tail 50 <service-name>`
   - Number: `docker logs --tail <N> <service-name>`
   - "error" / "warn" / any keyword: `docker logs --tail 500 <service-name> 2>&1 | grep -i "<keyword>"`
   - "follow": `docker logs -f --tail 50 <service-name>`

4. Container name mapping (all containers are prefixed with `nexus-` except audit-write-native):
   - audit-write-native → `nexus-audit-write-native`
   - All others → same as service directory name

5. If the container is not running, show the last known logs anyway:
```bash
docker logs --tail 100 <service-name> 2>&1
```
   Then tell the user the container is stopped.

6. Highlight ERROR and WARN lines in your response by quoting them.
