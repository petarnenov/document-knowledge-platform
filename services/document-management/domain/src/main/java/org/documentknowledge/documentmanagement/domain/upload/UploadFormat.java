package org.documentknowledge.documentmanagement.domain.upload;
public enum UploadFormat {
 PDF("application/pdf","pdf"), DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document","docx"),
 PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation","pptx"), XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","xlsx");
 private final String mime,extension;
 UploadFormat(String mime,String extension){this.mime=mime;this.extension=extension;}
 public String mime(){return mime;} public String extension(){return extension;}
}
