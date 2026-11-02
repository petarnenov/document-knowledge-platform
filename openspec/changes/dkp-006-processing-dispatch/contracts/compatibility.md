# Compatibility and controlled staging amendment

This DRAFT proposes the precise BP03→BP05 transition, not approval. Published contracts/SDKs are untouched. Frozen processing.openapi.json copies every currentDM/BFF path/schema first; existing nonAction schemas, requestDTOs, operationIds, statuses andresponses SHALL remain JSON-deep-equal. Its base is BP03dev62a73f0; approvedBP04 sole document.share-to-collection addition is incorporated into the Action candidate before the three newprocessingactions. The approved BP04 publication packet193 (independent review221, error amendment219/review220) is frozen in bp04-sharing-baseline.json with its source SHA256. Both sharing routes and all six added DTOs are copied exactly and checked independently; they cannot be overwritten during publication. The dependency must still integrate before implementation delivery.

| Surface | Preserved behavior | Explicit new behavior |
|---|---|---|
| upload complete200 | verificationreceipt only; no version/run | unchanged |
| dmAppendVersion/bffAppendVersion | POSTexistingversions path; AppendVersion DTO;201CommandReceipt; originalkeys/body/currentreplay | NEWcommands aftercutover internallysave version/run/intent/outbox together; neitherchanges201norclaimsprocessinghasstarted |
| originalprecutover201 | exactoriginal201minimalreceipt oncurrentauthorizedreplay | NEVER schedules a previouslyabsentrun justbyreplay |
| additiveprocessingacceptance | noexistingroute/statusreplaced | newoperationIds+202 closedreceipt proveatomicNEWquad |
| existingversionenrollment | immutableversion/content/creator/classification/publicationpointer | distinct202createsrun/intent/outbox, explicitlynotNEWquad |
| processingstatus | nolegacyDTOnewfields | newprivilegedGETversionprocessing returnsNOT_SCHEDULED or currentrun; opaqueids/time/stateonly |
| Action/catalog | original11values includingBP04share | ONLY request/inspect/cancel processing additions; sameACL/bounds/seed/delegationpolicy |

An old SDK may continue append201; the new SDK exposes distinct202methods andfinite run status/cancel/enrollment. No union201/202 substitution onoldoperation, no duplicateoperationId/DTOrenaming orgeneratorcollision. OpenAPIminorversion0.3.0candidate; event/workflow schemas1.0.0. SDKcompatcompilationoldconsumeragainstnew SDKmandatory. Catalog/Zod/backendActionmirror additions testedagainst original11positives+unknownnegative andnonActionequality; no blanketnewgrant.

Controlledamendment to historical upload-security Honest staged processing boundary: BP03completedreceiptcontinuesnoversion; verified BP05cutover unlocksadditive202 andatomicnewlegacyappend scheduling. Untilthatgate, BP03 no202/noPROCESSINGrestriction remains. Existingstageacceptanceisnotdeleted; no approvaloflaterparser/publication/production. Reviewers must explicitlyaccept thiscompatibilitypolicy andlegacyoldversionplan; developmentapproval alone grantsnoproductionCIDwaiver.

Migrationcannotbulkautoenrollunauthorizedoldversions orrewriteversionrows. Completecoverageinventoryandboundedexplicitcurrentauthorizedenrollment preventsilentomission. Unknownoriginalintegrity/retentionreuse makesenrollment503/blocked ratherthanmarkingprocessed. No202withoutdurablerecords. Contractcheck script freezesbaseline andfailsremoved/changedoldpaths/nonActionschemas, missingBFFparity, forbiddennew statuses onoldappend, unchangedB03raw contentlimits andBP04sharingwhenintegrated.

Dependency archive precondition: at this draft base, upload-security is still an active BP03 delta, not a canonicalspec. OfficialOpenSpec strict validation reports its MODIFIED target as not yet existing forarchive. BP03 must complete/archive beforeBP05archive (and before runtime perdependencies); then this narrow reviewedamendment applies tocanonicaltarget. No targetcreation or historicalscenario deletion is faked. PublishedBP03old201response still contains historical future-BP02description text; baselinechecker preserves it exactly, while normativeBP03/BP05cutover behavior here governs. Any laterdescriptioncleanup is a separately reviewed administrative contract delta.
