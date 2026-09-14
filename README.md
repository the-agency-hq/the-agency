# the-agency

The Agency web application that creates the Briefs that are then passed to the Handlers.

## License and deployment notice

This project is currently open source under the MIT license. However, it is not currently design to be easily run locally. We'll consider supporting that type of deployment at some point in the future though.

## Local FusionAuth

This project currently requires a licensed instance of FusionAuth as the Identity Provider. If you are part of the team, contact Brian for a FusionAuth developer license key.

To run FusionAuth locally, follow these steps:

```shell
cd src/main/fusionauth
cp .env.template .env
```

Next, edit the `.env` file and add your license key.

Then run FusionAuth in Docker:

```shell
docker compose up -d
```

FusionAuth is accessible at http://localhost:9016 and is preconfigured for the web application and the Handler CLI. You can test with these credentials:

* Admin user: admin@theagencyhq.dev/password
* Ordinary user: user@theagencyhq.dev/password

If you need any emails from FusionAuth, the Docker compose runs Mailcatcher at http://localhost:1080

### Latte framework

This project uses the Latte Web framework and HTTP server. You can learn more about those at https://lattejava.org. 

## Concepts

* Brief - the collection of rules, commands, skills, and other files used by Agents (LLMs)
* Organization - has a single Brief source, a connected GitHub, GitLab, or Bitbucket repository, that its Brief is built from
* Member - a user that is part of an Organization

## Brief sources

Users connect a repository on a host — GitHub, GitLab, or Bitbucket Cloud — to an Organization. This app polls the host for changes to the repository. Any changes that occur are downloaded and translated into a Brief. This is stored in the database and versioned.

A kind of source is offered on an Organization's **Sources** page only when the server holds its OAuth credentials (`github.clientId`/`github.clientSecret` for GitHub, `gitlab.clientId`/`gitlab.clientSecret` for GitLab, `bitbucket.clientId`/`bitbucket.clientSecret` for Bitbucket). None of them is required configuration: with none configured, the server starts and the Sources page says that no source is configured yet. An Organization holds one source, so connecting one kind replaces a source of the other.

### GitHub App

Connecting a repository goes through a GitHub App (not an OAuth App). Register one per environment — its callback and setup URLs are single values, so a development App points at `http://localhost:8080` — and configure it as follows:

| Setting                                                | Value                                                                                                                   |
|--------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| Callback URL                                           | `<base URL>/app/oauth/github/callback`                                                                                  |
| Expire user authorization tokens                       | On                                                                                                                      |
| Request user authorization (OAuth) during installation | Off (with it on, GitHub ignores the Setup URL and sends installs to the callback URL, which also returns to the picker) |
| Setup URL                                              | `<base URL>/app/oauth/github/setup`                                                                                     |
| Redirect on update                                     | On                                                                                                                      |
| Repository permissions                                 | Contents: read, Metadata: read                                                                                          |
| Webhook                                                | Off (not used)                                                                                                          |

Then put the App's slug and OAuth credentials in `~/.config/the-agency-hq/the-agency/config.properties`:

```properties
github.appName=<slug from https://github.com/apps/<slug>>
github.clientId=...
github.clientSecret=...
```

Connecting a repository is then: create an Organization, open its **Sources** page, connect GitHub (the OAuth authorization), and pick a repository. The picker lists every repository the App is installed on that your GitHub user can see. Its install links send you to GitHub to install the App on another account or change what an installation covers, and GitHub returns you to the picker, which lists again. The source, its credential included, is stored as one JSON document on the Organization's `brief_sources` row; changing the repository keeps the credential, and a lapsed credential keeps the repository.

### GitLab application

Connecting a GitLab project goes through an OAuth application on the GitLab instance (**User Settings → Applications**, or a group or instance application). Register one per environment and configure it as follows:

| Setting      | Value                                  |
|--------------|----------------------------------------|
| Redirect URI | `<base URL>/app/oauth/gitlab/callback` |
| Confidential | On                                     |
| Scopes       | `read_api`                             |

Then put its credentials in `~/.config/the-agency-hq/the-agency/config.properties`:

```properties
gitlab.clientId=...
gitlab.clientSecret=...
# Only for a self-managed instance; defaults to https://gitlab.com
gitlab.baseURL=https://gitlab.example.com
```

Connecting a project is the same workflow: open the Organization's **Sources** page, connect GitLab, and pick a project. The picker lists every project the authorizing account is a member of, directly or through a group, by its full path (`group/subgroup/project`); there is no install step, so an account that is a member of no project is told to fix that on GitLab. GitLab access tokens expire after two hours and are refreshed in place by the poller with the refresh token GitLab issues alongside them.

### Bitbucket OAuth consumer

Connecting a Bitbucket Cloud repository goes through an OAuth consumer on a Bitbucket workspace (**Workspace settings → OAuth consumers**). Register one per environment and configure it as follows:

| Setting                    | Value                                     |
|----------------------------|-------------------------------------------|
| Callback URL               | `<base URL>/app/oauth/bitbucket/callback` |
| This is a private consumer | On                                        |
| Permissions                | Account: Read, Repositories: Read         |

Then put its key and secret in `~/.config/the-agency-hq/the-agency/config.properties`:

```properties
bitbucket.clientId=<the consumer's key>
bitbucket.clientSecret=<the consumer's secret>
```

Connecting a repository is the same workflow: open the Organization's **Sources** page, connect Bitbucket, and pick a repository. The picker lists every repository the authorizing account can read, across every workspace it belongs to, by its full name (`workspace/repository`); there is no install step, so an account that can read no repository is told to fix that on Bitbucket. Bitbucket access tokens expire after two hours and are refreshed in place by the poller with the refresh token Bitbucket issues alongside them. Bitbucket Data Center is not supported.

### Agent selection

An Organization's Owners choose which Agents it uses, from the Organization's (**Agents**) page. The default is **All**. Every Brief is still built and stored for every Agent; the selection is applied when a Handler polls, which is served only the files the selected Agents read (shared files such as `.agents/skills/` stay as long as one selected Agent reads them). Changing the selection publishes the latest Brief again as a new version, so Handlers pick it up on their next poll without a rebuild. The selection is stored as JSON on the `organizations` row (`NULL` = All).

## Building and testing

This project uses Latte's CLI as the build and project management system. Here are some commands:

| Command               | Description                                                    |
|-----------------------|----------------------------------------------------------------|
| `latte build`         | Builds the project                                             |
| `latte main-database` | Creates the project's main database in a local Postgres server |
| `latte test-database` | Creates the project's test database in a local Postgres server |
| `latte test`          | Runs the tests                                                 |
| `latte run`           | Runs the webapp locally                                        |
| `latte deploy`        | Deploys the webapp to Railway                                  |

## Coming soon

- [ ] Subagent translation beyond name, description and prompt: tools, permission mode, skills, turn limits, effort.
- [ ] Build-time warnings surfaced in the admin UI (persistent): per-agent size caps, skill name rules.
- [ ] Commands translated to each agent's slash-command format.
- [ ] Hooks translated to each agent's hook format.
- [ ] Per-agent skill extensions, such as Codex's `agents/openai.yaml`.
- [ ] Qwen Code support.

# Contributing

If you would like to contribute to this project, contact Brian Pontarelli (@voidamin).