[#ftl/]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="showCaptcha" type="boolean" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#import "../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("password-forgot-page-title")]
    [@helpers.captchaScripts showCaptcha=showCaptcha captchaMethod=tenant.captchaConfiguration.captchaMethod siteKey=tenant.captchaConfiguration.siteKey/]
    [#-- Custom <head> code goes here --]
  [/@helpers.head]
  [@helpers.body]
    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [@helpers.main title=theme.message('forgot-password-title')]
      <p class="mt-0 mb-3">
        ${theme.message('forgot-password')}
      </p>

      [@helpers.structuredForm action="${request.contextPath}/password/forgot" method="POST"; section]
        [#if section == "formFields"]
          [@helpers.oauthHiddenFields/]

          [@helpers.input type="text" name="loginId" id="loginId" autocapitalize="none" autofocus=true autocomplete="on" autocorrect="off" placeholder=theme.message('loginId') leftAddon="user" required=true/]
          [@helpers.captchaBadge showCaptcha=showCaptcha captchaMethod=tenant.captchaConfiguration.captchaMethod siteKey=tenant.captchaConfiguration.siteKey/]
        [#elseif section == "buttons"]
          [@helpers.button text=theme.message('submit')/]
          <p class="mt-2">[@helpers.link url="/oauth2/authorize"]${theme.message('return-to-login')}[/@helpers.link]</p>
        [/#if]
      [/@helpers.structuredForm]
    [/@helpers.main]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
