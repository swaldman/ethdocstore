* Finish implementation of file access authentication
* Implement client-side register
* Implement client-settable visibility
* Handle more content types
* Use interaction rather than a long command line for ingests
* Streaming / chinking large uploads as well as downloads
* Implement client-side hash verification
  - Don't think a server-side recheck is worth it
    - But maybe asynchronously, test for "corruption"?
      - notify / git push / publicly log "corruption"?
* Think a lot more about consistency in load-balanced / multi-server use case
  - Instead of treating file puts as repeatable and idempotent, maybe guarantee at-most-once semantics with some form of atomic entry and commit
* Authentication is HTTP Basic, but intended users are host-your-own small orgs, is expecting https too much?