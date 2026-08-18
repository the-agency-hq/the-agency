[#ftl/]
[#-- @ftlvariable name="application" type="io.fusionauth.domain.Application" --]
[#-- @ftlvariable name="cause" type="java.lang.String" --]
[#-- @ftlvariable name="client_id" type="java.lang.String" --]
[#-- @ftlvariable name="currentUser" type="io.fusionauth.domain.User" --]
[#-- @ftlvariable name="incidentId" type="java.lang.String" --]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#import "_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("unauthorized-page-title")/]
  [@helpers.body]

    [@helpers.main title=theme.message("access-denied") colClass="col-xs col-sm-10 col-md-8 col-lg-7 col-xl-5"]
      <div class="flex flex-col gap-4">


        [#assign incidentId = (request.getAttribute("incidentId")!'')/]
        [#assign incidentCause = (request.getAttribute("incidentCause")!'')/]

        [#if incidentCause?has_content && incidentCause == "BlockedIPAddressException"]
        ${theme.message('unauthorized-message-blocked-ip', currentBaseURL)}
        [#else]
        ${theme.message("unauthorized-message")}
        [/#if]
        <p>${theme.message("ip-address")}${theme.message("propertySeparator")} ${currentIPAddress}

        [#assign currentLocation = fusionAuth.currentLocation()!{}/]
        [#if currentLocation?has_content]
        ${theme.message("location")}${theme.message("propertySeparator")} ${currentLocation.displayString}
        [/#if]
        </p>

        <hr>
         <p>[#if incidentId?has_api]${theme.message("incident-id")}${theme.message("propertySeparator")} <span class="font-semibold">${incidentId}</span> &middot; [/#if] </p>

         <p class="text-xs">${theme.message("security-by")} [@helpers.link url="https://fusionauth.io"]FusionAuth[/@helpers.link]</p>
       </div>
    [/@helpers.main]

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]
