package dev.theagencyhq.agency.tests;

import module org.lattejava.web;
import module org.testng;

import dev.theagencyhq.agency.Main;

@Test
public class MainTest {
  public Main main = new Main();
  public StringBodyAsserter string = new StringBodyAsserter();
  public WebTest test = new WebTest(Main.PORT);

  @AfterSuite
  public void afterSuite() {
    main.close();
  }

  @BeforeSuite
  public void beforeSuite() {
    main.main();
  }

  @Test
  public void getSlash() {
    test.get("/")
        .assertStatus(200)
        .assertBodyAs(string, j -> j.equalTo("Welcome to Latte Java!"));
  }
}
