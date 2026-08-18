[#ftl/]
[#-- @ftlvariable name="showCaptcha" type="boolean" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#import "../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("oauth2-webauthn-page-title")]
    [@helpers.captchaScripts showCaptcha=showCaptcha captchaMethod=tenant.captchaConfiguration.captchaMethod siteKey=tenant.captchaConfiguration.siteKey/]
    <script src="${request.contextPath}/js/FormHelper.js?version=${version}"></script>
    <script src="${request.contextPath}/js/oauth2/OAuth2WebAuthnLogin.js?version=${version}"></script>
    <script>
      document.addEventListener('DOMContentLoaded', () => {
        new OAuth2WebAuthnLogin(
          document.querySelector('form[id="webauthn-login-form"]'),
          document.querySelector('input[type="hidden"][name="webAuthnRequest"]')
        );
      });
    </script>
  [/@helpers.head]

  [@helpers.body]
    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]
    [@helpers.main title=theme.message("no-password")]
      [#setting url_escaping_charset='UTF-8']
        [@helpers.structuredForm id="webauthn-login-form" action="${request.contextPath}/oauth2/webauthn" method="POST"; section]
          [#if section == "formFields"]
            [@helpers.oauthHiddenFields/]
            [@helpers.hidden name="webAuthnRequest" /]
            [@helpers.hidden name="workflow" value="bootstrap"/]
            [@helpers.hidden name="userVerifyingPlatformAuthenticatorAvailable" /]

            <div>
              ${theme.message("{description}webauthn-bootstrap-retrieve-credential")}
            </div>
            [@helpers.input type="text" name="loginId" id="loginId" autocomplete="username" autocapitalize="none" autocomplete="on" autocorrect="off" spellcheck="false" autofocus=true placeholder=theme.message("loginId") leftAddon="user" required=true/]
            [@helpers.captchaBadge showCaptcha=showCaptcha captchaMethod=tenant.captchaConfiguration.captchaMethod siteKey=tenant.captchaConfiguration.siteKey/]

            [@helpers.input id="rememberDevice" type="checkbox" name="rememberDevice" label=theme.message('remember-device') value="true" uncheckedValue="false" tooltip=theme.message("{tooltip}remember-device")/]
          [#elseif section == "buttons"]
            [@helpers.button text=theme.message("submit")/]
            <p class="mt-2">[@helpers.link url="/oauth2/authorize" extraParameters="&skipWebAuthnReauth=true"]${theme.message("return-to-normal-login")}[/@helpers.link]</p>
          [/#if]
        [/@helpers.structuredForm]
    [/@helpers.main]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
