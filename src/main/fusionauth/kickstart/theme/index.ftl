[#ftl/]
[#-- @ftlvariable name="tenant" type="io.fusionauth.domain.Tenant" --]
[#-- @ftlvariable name="tenantId" type="java.util.UUID" --]
[#-- @ftlvariable name="theme" type="io.fusionauth.domain.Theme" --]
[#import "_helpers.ftl" as helpers/]

[@helpers.html]
  [@helpers.head title=theme.message("index-page-title")/]
  [@helpers.body]
       [#--
        Example landing page. You can use a meta refresh (or via JS) to redirect users who land here to your primary site,
         or add some links here to help direct them to different applications.

         For additional details on performing a redirect.
         https://developer.mozilla.org/en-US/docs/Web/HTTP/Redirections#alternative_way_of_specifying_redirections
        --]
        <main class="min-h-screen bg-page-bg flex flex-col items-center px-5 py-15">
          <div class="w-full max-w-lg rounded-theme">
            <div class="bg-panel-bg border-1 border-panel-border rounded-panel shadow-sm">
              <div class="p-8">  
                <main>
                  <div class="p-3 pb-5">
                    <img class="w-48 mb-8" src="/images/logo-gray.svg">
                    <p class="text-lg mb-2">Welcome to your FusionAuth instance!</p>
                    <div class="text-sm">
                    <p class="mb-3">
                      If you're looking for <strong>the FusionAuth admin application</strong>, you'll find that at <strong><a class="text-indigo-800 underline" href="/admin">/admin</a></strong>. FusionAuth has a handful of applications running on it, each at a different path. This page is just the default page for the system.
                    </p>
                      <p>
                        You can theme this in your <a href="/admin/theme" class="text-indigo-800 underline">customization options</a>. If you don't want to theme it, be sure to redirect it to the proper route.
                      </p>     
                      <a class="flex justify-center items-center mb-9 mt-5 rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white shadow-xs hover:bg-indigo-600 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500 cursor-pointer" href="/admin/">
                        <span>Go to FusionAuth Admin</span>
                        <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="18" height="16" class="ml-2">
                          <path fill="none" stroke="white" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" transform="translate(0.75 0.823242)" d="M8.75 0L15 6.25M15 6.25L8.75 12.5M15 6.25L0 6.25" fill-rule="evenodd"></path>
                        </svg>
                      </a>
                      
                      <h2 class="text-lg mb-3">Need Help?</h2>
                      <p class="mb-2">If you want help setting up your instance with best-practice expertise, <a class="text-indigo-800 underline" href="https://fusionauth.link/root-contact">reach out to the sales team</a> to set up a meeting. Want to tinker and figure it out on your own? Here are some links to help you get started:</p>
                      <ul class="pl-4">
                      <li class="pt-2 list-disc">[@helpers.link url="https://fusionauth.link/root-get-started"]Getting started[/@helpers.link]</li>
                      <li class="pt-2 list-disc">[@helpers.link url="https://fusionauth.link/root-look-and-feel"]Customization and Themes[/@helpers.link]</li>
                      <li class="pt-2 list-disc">[@helpers.link url="https://fusionauth.link/root-docs"]Full Documentation[/@helpers.link]</li>
                      <li class="pt-2 list-disc">[@helpers.link url="https://fusionauth.link/root-community"]Community & Support[/@helpers.link]</li>
                    </ul>
                    </div>
                  </div>
                </main>
              </div>
            </div>
          </div>
        </main>

    [@helpers.footer]
      [#-- Custom footer code goes here --]
    [/@helpers.footer]
  [/@helpers.body]
[/@helpers.html]