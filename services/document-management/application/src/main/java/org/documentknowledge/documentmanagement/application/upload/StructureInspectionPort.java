package org.documentknowledge.documentmanagement.application.upload;
import org.documentknowledge.documentmanagement.domain.upload.UploadModels.InspectionEvidence;
@FunctionalInterface public interface StructureInspectionPort { InspectionEvidence inspect(ArtifactSource source); }
