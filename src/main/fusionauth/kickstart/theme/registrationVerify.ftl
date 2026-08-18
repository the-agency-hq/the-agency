[#ftl/]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="showCaptcha" type="boolean" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#-- @ftlvariable name="verificationId" type="java.lang.String" --]
[#import "../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("registration-verify-page-title")]
    [@helpers.captchaScripts showCaptcha=showCaptcha captchaMethod=tenant.captchaConfiguration.captchaMethod siteKey=tenant.captchaConfiguration.siteKey/]
  [/@helpers.head]
  [@helpers.body]
    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [@helpers.main title=theme.message('registration-verification-form-title')]
      [#-- FusionAuth automatically handles errors that occur during registration verification and outputs them in the HTML --]
      [@helpers.structuredForm action="${request.contextPath}/registration/verify" method="POST"; section]
        [#if section == "formFields"]
          [@helpers.hidden name="captcha_token"/]
          [@helpers.hidden name="client_id"/]
          [@helpers.hidden name="tenantId"/]
          <p>
            ${theme.message('registration-verification-form')}
          </p>
          <fieldset class="push-less-top">
            [@helpers.input type="text" name="email" id="email" autocapitalize="none" autofocus=true autocomplete="on" autocorrect="off" placeholder="${theme.message('email')}" leftAddon="user"/]
            [@helpers.captchaBadge showCaptcha=showCaptcha captchaMethod=tenant.captchaConfiguration.captchaMethod siteKey=tenant.captchaConfiguration.siteKey/]
          </fieldset>
        [#elseif section == "buttons"]
          <div class="form-row">
            [@helpers.button text=theme.message('submit')/]
          </div>
        [/#if]
      [/@helpers.structuredForm]
    [/@helpers.main]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
