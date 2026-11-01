# Independently checked primary evidence — 2026-11-01

Pinned effective artifact is `io.micronaut:micronaut-http-server-netty:5.2.20`, with Netty4.2.19.Final and repository JDK25.0.5. Source/binary fingerprints are frozen in readback-profile.json; they were computed from the exact public Maven-cache artifacts used by338, not from a later documentation version.

| Primary source | Finding |
| --- | --- |
| [PipeliningServerHandler v5.2.20](https://github.com/micronaut-projects/micronaut-core/blob/v5.2.20/http-server-netty/src/main/java/io/micronaut/http/server/netty/handler/PipeliningServerHandler.java#L98) | final `@Internal` class; private fields120–121; public void setters184–197 without matching policy getters/events. The decompression path620–648 constructs an embedded decoder before stripping encoding/length headers. Body policy is also used at652,755–760,803 and871; readComplete/error can devolve optimistic buffering to streaming. |
| [HttpPipelineBuilder v5.2.20](https://github.com/micronaut-projects/micronaut-core/blob/v5.2.20/http-server-netty/src/main/java/io/micronaut/http/server/netty/HttpPipelineBuilder.java#L855) | Constructs the actual handler, initializes policy then publishes that same instance in the pipeline; configuration does not mirror subsequent handler setter calls. |
| [NettyServerCustomizer v5.2.20](https://github.com/micronaut-projects/micronaut-core/blob/v5.2.20/http-server-netty/src/main/java/io/micronaut/http/server/netty/NettyServerCustomizer.java) | Supported initial/stream-pipeline construction callbacks; no live effective-policy state or mutation-notification API. Its documentation warns internal pipeline structure is unstable. |

Additional exact source-content SHA256: NettyServerCustomizer.java `d5f7334673edb90f58afb3988bf7d14a9b7ca653b29e39df42e7ab5befc089f2`; HttpPipelineBuilder.java `161fe464e759d2d286c9f86e072a0481aee2973a4298cb9c4d955c6ea3124711`. Pipelining source hash is in the descriptor. These are public upstream sources, not internal/employer architecture.

The standalone `probes/PublicIngressPolicyProbe.java` was fresh-compiled with repository javac25.0.5 against the actual338 pinned runtime classpath. It executed without a network connection, body/request dispatch or private field reads. Three exact outcomes:

- `PROBE_PASS same_instance_decompression_setter_leaves_public_config_and_identity_unchanged`
- `PROBE_PASS same_instance_limit_setter_leaves_public_config_and_identity_unchanged`
- `PROBE_PASS public_policy_setters_have_no_readback_api`

Private local logs `/tmp/dkp366-probe-compile.log` and `/tmp/dkp366-public-probe.log` preserve the successful compile/run; the code and results are independently reproducible with pinned dependencies. The setter assignments' hidden effect follows the pinned source; the probe does not pretend to read inaccessible state publicly. It demonstrates why config/identity observations are insufficient before any body; it is not a production reflection adapter or a full server/resource acceptance test.

No maintained future upstream API release is invented. Option A needs actual release research/pins and re-review if pursued. The broader docs.micronaut.io latest API page is informative but is not substituted for the exact5.2.20 source in this decision.
