package com.sakiv.cpi.snapshot.api;

/** Design-time artifact types that the OData API can download as a ZIP. */
public enum ArtifactType {

    IFLOW("IntegrationDesigntimeArtifacts", "iflows"),
    VALUE_MAPPING("ValueMappingDesigntimeArtifacts", "value-mappings"),
    MESSAGE_MAPPING("MessageMappingDesigntimeArtifacts", "message-mappings"),
    SCRIPT_COLLECTION("ScriptCollectionDesigntimeArtifacts", "script-collections");

    private final String entitySet;
    private final String folder;

    ArtifactType(String entitySet, String folder) {
        this.entitySet = entitySet;
        this.folder = folder;
    }

    public String entitySet() {
        return entitySet;
    }

    /** Folder name used under each package directory in the snapshot. */
    public String folder() {
        return folder;
    }
}
