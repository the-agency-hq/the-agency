# Copyright (c) 2026 The Agency HQ
# SPDX-License-Identifier: MIT
#
# Railway image for FusionAuth. Railway has no bind mounts, so the kickstart directory the
# compose stack mounts is baked into the image instead. Kickstart only ever applies to an
# empty database, so redeploys of this image are configuration no-ops. Keep the image tag in
# lockstep with docker-compose.yml -- one FusionAuth version, both environments.
FROM fusionauth/fusionauth-app:1.67.1

COPY kickstart/ /usr/local/fusionauth/kickstart/
