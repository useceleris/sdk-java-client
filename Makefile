# Builds, checks and publishes com.useceleris:celeris-client. Run from the
# repository root. JAVA_HOME selects the JDK; 21 or newer also runs Error
# Prone, NullAway and the format check.
#
#   make check                 the unit and integration suites, with analysis
#   make format                apply google-java-format
#   make live                  the acceptance suites against a real Celeris stack, from .env
#   make version VERSION=x.y.z set the project version
#   make install               install the artifact in ~/.m2, for the server build
#   make release-check         build and sign the release artifacts, without upload
#   make publish               sign and upload a release version to Maven Central
#
# Publishing needs a GPG signing key and a Central Portal token in
# ~/.m2/settings.xml under the server id "central". The upload waits in the
# Central Portal until you publish it there.

MVN := ./mvnw
VERSIONS_PLUGIN := org.codehaus.mojo:versions-maven-plugin:2.22.0

.PHONY: check format live version install release-check publish require-release-version

check:
	$(MVN) verify

format:
	$(MVN) spotless:apply

live:
	$(MVN) verify -Plive

version:
	@test -n "$(VERSION)" || { echo "Usage: make version VERSION=x.y.z"; exit 1; }
	$(MVN) $(VERSIONS_PLUGIN):set -DnewVersion=$(VERSION) -DgenerateBackupPoms=false

install:
	$(MVN) install -DskipTests

release-check: require-release-version
	$(MVN) -Prelease clean verify

publish: require-release-version
	$(MVN) -Prelease clean deploy

require-release-version:
	@version="$$($(MVN) -q help:evaluate -Dexpression=project.version -DforceStdout)"; \
	case "$$version" in \
	  *-SNAPSHOT) echo "$$version is a snapshot. Run make version VERSION=x.y.z first."; exit 1;; \
	esac
