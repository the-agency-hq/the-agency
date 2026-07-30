package dev.theagencyhq.agency;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

@SuppressWarnings("resource")
public class Main {
  public static final int PORT = 8080;
  private final JTETemplates templates = new JTETemplates(Path.of("web/templates"), Path.of("build"));
  public final Web web = new Web();

  public void close() {
    web.close();
  }

  public void main() {
    web.install(SecurityHeaders.defaults())
       .baseDir(Path.of("web"))
       .files("/static")
       .get("/", templates::html)
       .start(PORT);
  }
}
