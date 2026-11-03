package org.documentknowledge.documentmanagement.application.upload;

import org.documentknowledge.documentmanagement.application.DiagnosticContext;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.InspectionEvidence;

@FunctionalInterface
public interface StructureInspectionPort {
    InspectionEvidence inspect(ArtifactSource source);
    default InspectionEvidence inspect(ArtifactSource source,CompleteBudget.Phase phase) {
        phase.check();var result=inspect(source);phase.check();return result;
    }
    default InspectionEvidence inspect(ArtifactSource source,CompleteBudget.Phase phase,UploadDiagnosticsPort.FailureObserver observer) {
        return inspect(source,phase);
    }
    default InspectionEvidence inspect(ArtifactSource source,CompleteBudget.Phase phase,UploadDiagnosticsPort.FailureObserver observer,DiagnosticContext context) {
        return inspect(source,phase,observer);
    }
}
