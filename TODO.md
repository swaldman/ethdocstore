* Better formatted "cover page" showing the index of docstores served by the webapp
* Implement user-only permissions for webservice created directories and files
  - Maybe enforce such permissions on webservice app startup
* Streaming / chinking large uploads as well as downloads
* Implement client-side hash verification
  - Don't think a server-side recheck is worth it
    - But maybe asynchronously, test for "corruption"?
      - notify / git push / publicly log "corruption"?
* Think a lot more about consistency in load-balanced / multi-server use case
  - Instead of treating file puts as repeatable and idempotent, maybe guarantee at-most-once semantics with some form of atomic entry and commit
* Authentication is HTTP Basic, but intended users are host-your-own small orgs, is expecting https too much?
* Migrate to elsewhere
  - file relativizer
  - password manager
  