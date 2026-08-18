[#ftl/]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="currentUser" type="io.fusionauth.domain.User" --]
[#-- @ftlvariable name="recoveryCodes" type="java.util.List<java.lang.String>" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#-- @ftlvariable name="version" type="java.lang.String" --]
[#import "../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("oauth2-two-factor-enable-complete-page-title")]
    [#-- Custom <head> code goes here --]
  [/@helpers.head]
  [@helpers.body]

    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [@helpers.main title=theme.message("two-factor-recovery-codes")]
      [#setting url_escaping_charset='UTF-8']
      [@helpers.structuredForm action="${request.contextPath}/oauth2/two-factor-enable-complete" method="POST"; section]
        [#if section == "formFields"]
          [@helpers.oauthHiddenFields/]
          ${theme.message("{description}oauth2-recovery-codes-1")}

          [@helpers.codeBox]
            [#list recoveryCodes as code]
${code}
            [/#list]
          [/@helpers.codeBox]

          ${theme.message("{description}oauth2-recovery-codes-2")}
        [#elseif section == "buttons"]
          [@helpers.button text=theme.message("done")/]
        [/#if]
      [/@helpers.structuredForm]
    [/@helpers.main]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
