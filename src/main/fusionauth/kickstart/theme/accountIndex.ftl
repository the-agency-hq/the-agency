[#ftl/]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="formConfigured" type="boolean" --]
[#-- @ftlvariable name="multiFactorAvailable" type="boolean" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#-- @ftlvariable name="user" type="io.fusionauth.domain.User" --]
[#-- @ftlvariable name="webauthnAvailable" type="boolean" --]

[#import "../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("account-index-page-title")]
    [#-- Custom header code goes here --]
    <script src="${request.contextPath}/js/ui/Main.js?version=${version}"></script>
  [/@helpers.head]
  [@helpers.body]
    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [#assign actionURLs = multiFactorAvailable?then(["/account/two-factor/"], [])/]
    [#assign actionURLs = actionURLs + webauthnAvailable?then(["/account/webauthn/"], [])/]

    [#assign actionTexts = multiFactorAvailable?then([theme.message("manage-two-factor")], [])/]
    [#assign actionTexts = actionTexts + webauthnAvailable?then([theme.message("manage-webauthn-passkeys")], [])/]

    <main class="min-h-screen bg-page-bg flex flex-col items-center px-5 py-15">
      [@helpers.printErrorAlerts rowClass="flex justify-center" colClass="w-full max-w-[352px]"/]
      [@helpers.printInfoAlerts rowClass="flex justify-center" colClass="w-full max-w-[352px]"/]
      
      [#-- Main Card --]
      <div class="w-full max-w-[352px] bg-panel-bg border-2 border-panel-border rounded-theme shadow-sm">
        <div class="p-8">
          <div class="flex flex-col items-center gap-6 mb-6">
            [#-- Profile Image --]
            <div class="w-36 h-36 rounded-full overflow-hidden">
              [#if user.imageUrl??]
                <img src="${user.imageUrl}" class="w-full h-full object-cover" alt="profile image"/>
              [#elseif user.lookupEmail()??]
                <img src="${function.gravatar(user.lookupEmail(), 144)}" class="w-full h-full object-cover" alt="profile image"/>
              [#else]
                <img src="${request.contextPath}/images/missing-user-image.svg" class="w-full h-full object-cover" alt="profile image"/>
              [/#if]
            </div>
            
            [#-- User Name --]
            <div class="text-lg font-medium text-text">${helpers.display(user, "name")}</div>
          </div>

          [#-- Email and Phone section --]
          <div class="space-y-4 mb-6">
            [#-- Email field - NOT an input, just display --]
            <div>
              <div class="text-xs font-medium text-text mb-1">${theme.message("user.email")}</div>
              <div class="flex items-center justify-start gap-2">
                <span class="text-sm text-text">${helpers.display(user, "email")}</span>
                [#if user.verified]
                  <svg class="w-[18px] h-[18px] flex-shrink-0 fill-primary  text-text" width="18" height="18" fill="none" viewBox="0 0 18 18" xmlns="http://www.w3.org/2000/svg">
                    <title>Email has been verified</title>
                    <path d="M0 9c0 4.971 4.029 9 9 9s9-4.029 9-9c0-4.971-4.029-9-9-9S0 4.029 0 9zm6.075.787c-.281-.373-.204-.9.173-1.171.373-.281.9-.204 1.171.173L8.374 10.04 10.533 6.585c.246-.393.766-.517 1.164-.267.397.25.517.767.267 1.164L9.151 11.981c-.148.235-.401.383-.678.397-.278.014-.545-.112-.71-.336L6.075 9.787z"/>
                    <path class="fill-text" d="M11.696 6.314c.394.246.517.766.267 1.164L9.151 11.978c-.148.236-.401.383-.678.397-.278.014-.545-.112-.71-.337L6.075 9.787c-.281-.373-.204-.9.173-1.171.373-.281.9-.204 1.171.173L8.374 10.04 10.533 6.585c.246-.393.766-.517 1.164-.267z"/>
                  </svg>
                [/#if]
              </div>
            </div>

            [#-- Phone field - NOT an input, just display --]
            <div>
              <div class="text-xs font-medium text-text mb-1">${theme.message("user.phoneNumber")}</div>
              [#-- Only format an actual number; the stock template's \x2013 placeholder does not survive the
                   kickstart's file embedding, and phone_format would render the mangled text as a number. --]
              <div class="text-sm text-text">[#if user.phoneNumber??]${fusionAuth.phone_format(user.phoneNumber)}[#else]–[/#if]</div>
            </div>
          </div>

          [#-- Edit button if form configured --]
          [#if formConfigured]
            <a id="edit-profile" class="block w-full rounded-md bg-primary px-3 py-2 text-center text-sm font-semibold text-primary-text shadow-sm hover:bg-primary-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary" href="${request.contextPath}/account/edit?client_id=${client_id}&tenantId=${tenantId!''}">
              ${theme.message('edit-profile')}
            </a>
          [/#if]
        </div>
      </div>

      [#-- Action links for 2FA/WebAuthn - space-y-3 between card and locale --]
      [#if multiFactorAvailable || webauthnAvailable]
        <div class="w-full max-w-[352px] mt-3 text-center space-y-2">
          [#if multiFactorAvailable]
            <div>
              <a href="${request.contextPath}/account/two-factor/?client_id=${client_id}&tenantId=${tenantId!''}" class="text-link hover:text-link-hover text-sm">
                ${theme.message("manage-two-factor")}
              </a>
            </div>
          [/#if]
          [#if webauthnAvailable]
            <div>
              <a href="${request.contextPath}/account/webauthn/?client_id=${client_id}&tenantId=${tenantId!''}" class="text-link hover:text-link-hover text-sm">
                ${theme.message("manage-webauthn-passkeys")}
              </a>
            </div>
          [/#if]
        </div>
      [/#if]

      [#-- Locale selector at bottom with mt-3 spacing --]
      <div class="w-full flex justify-center max-w-[352px] mt-3">
        <div class="w-min-content">[@helpers.localeSelector/]</div>
      </div>
    </main>

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
