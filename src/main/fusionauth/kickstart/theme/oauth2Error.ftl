[#ftl/]
[#-- @ftlvariable name="oauthJSONError" type="java.lang.String" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#import "../_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("oauth2-error-page-title")]
    [#-- Custom <head> code goes here --]
  [/@helpers.head]
  [@helpers.body]

    [@helpers.header]
      [#-- Custom header code goes here --]
    [/@helpers.header]

    [@helpers.main title="Error"]
      <p class="text-sm">We're sorry, your request was malformed or was unable to be completed for some reason. Try hitting the back button and restarting the process to see if it fixes the problem.</p>

      [#if oauthJSONError?has_content]
      <p class="mt-4 mb-4">If you want the nerdy explanation, review the following JSON body.</p>
      <div class="overflow-auto text-xs full">
        <pre>${oauthJSONError}</pre>
      </div>
      [/#if]
    [/@helpers.main]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]

  [/@helpers.body]
[/@helpers.html]
