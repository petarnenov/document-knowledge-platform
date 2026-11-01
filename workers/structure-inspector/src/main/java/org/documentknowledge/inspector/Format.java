package org.documentknowledge.inspector;

public enum Format {
    PDF("application/pdf", null, null, null),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "word/document.xml", "document", "http://schemas.openxmlformats.org/wordprocessingml/2006/main"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", "ppt/presentation.xml", "presentation", "http://schemas.openxmlformats.org/presentationml/2006/main"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xl/workbook.xml", "workbook", "http://schemas.openxmlformats.org/spreadsheetml/2006/main");
    final String mime, mainPart, root, namespace;
    Format(String mime, String mainPart, String root, String namespace) { this.mime=mime;this.mainPart=mainPart;this.root=root;this.namespace=namespace; }
    public String mime() { return mime; }
}
