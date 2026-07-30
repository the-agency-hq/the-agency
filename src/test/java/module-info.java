module dev.theagencyhq.agency.tests {
  requires dev.theagencyhq.agency;
  requires org.lattejava.http;
  requires org.lattejava.web;
  requires org.testng;

  opens dev.theagencyhq.agency.tests to org.testng;
}
