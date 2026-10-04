"""TASK:PLT-04.E: rendered staging manifests keep internal services private and images immutable."""
import re
import shutil
import subprocess
import unittest

import yaml

from cfn import ROOT

ROOT_RUNNING_EXCEPTIONS = {"storefront"}  # nginx image runs as root; follow-up: nginx-unprivileged.


def render():
    kubectl = shutil.which("kubectl")
    if kubectl is None:
        raise unittest.SkipTest("kubectl (kustomize) is required to render manifests")
    out = subprocess.run([kubectl, "kustomize", str(ROOT / "infra/environments/staging")],
                         capture_output=True, text=True, check=True).stdout
    return [d for d in yaml.safe_load_all(out) if d]


class StagingManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docs = render()
        cls.workloads = [d for d in cls.docs if d["kind"] in {"Deployment", "StatefulSet"}]

    def kinds(self, kind):
        return [d for d in self.docs if d["kind"] == kind]

    def test_everything_lives_in_the_staging_namespace(self):
        for doc in self.docs:
            if doc["kind"] != "Namespace":
                with self.subTest(kind=doc["kind"], name=doc["metadata"]["name"]):
                    self.assertEqual(doc["metadata"]["namespace"], "fashion-staging")

    def test_only_gateway_and_storefront_are_reachable_from_ingress(self):
        (ingress,) = self.kinds("Ingress")
        backends, paths = set(), []
        for rule in ingress["spec"]["rules"]:
            for path in rule["http"]["paths"]:
                backends.add(path["backend"]["service"]["name"])
                paths.append(path["path"])
        self.assertEqual(backends, {"gateway", "storefront"})
        self.assertFalse([p for p in paths if p.startswith("/internal") or p.startswith("/actuator")])
        self.assertTrue(ingress["spec"]["tls"])
        annotations = ingress["metadata"].get("annotations", {})
        # spec.tls alone still lets Traefik serve the app on the plain-HTTP "web" entrypoint.
        self.assertEqual(annotations.get("traefik.ingress.kubernetes.io/router.entrypoints"), "websecure")
        self.assertEqual(annotations.get("traefik.ingress.kubernetes.io/router.tls"), "true")

    def test_services_are_cluster_internal(self):
        for service in self.kinds("Service"):
            with self.subTest(service=service["metadata"]["name"]):
                self.assertEqual(service["spec"].get("type", "ClusterIP"), "ClusterIP")
        gateway = next(s for s in self.kinds("Service") if s["metadata"]["name"] == "gateway")
        self.assertEqual([p["port"] for p in gateway["spec"]["ports"]], [8080], "management port not in Service")

    def test_images_are_digest_pinned(self):
        for workload in self.workloads:
            for container in workload["spec"]["template"]["spec"]["containers"]:
                with self.subTest(container=container["name"]):
                    self.assertRegex(container["image"], r"@sha256:[0-9a-f]{64}$")
                    self.assertNotIn(":latest", container["image"])

    def test_containers_have_probes_resources_and_hardened_context(self):
        for workload in self.workloads:
            pod = workload["spec"]["template"]["spec"]
            self.assertFalse(pod.get("automountServiceAccountToken", True), workload["metadata"]["name"])
            for container in pod["containers"]:
                with self.subTest(container=container["name"]):
                    self.assertIn("readinessProbe", container)
                    self.assertIn("livenessProbe", container)
                    for kind in ("requests", "limits"):
                        self.assertEqual(set(container["resources"][kind]), {"cpu", "memory"})
                    context = container["securityContext"]
                    self.assertFalse(context["allowPrivilegeEscalation"])
                    self.assertIn("ALL", context["capabilities"]["drop"])
                    if workload["metadata"]["name"] not in ROOT_RUNNING_EXCEPTIONS:
                        self.assertTrue(context["runAsNonRoot"])

    def test_no_secret_values_in_git(self):
        self.assertEqual(self.kinds("Secret"), [])
        for workload in self.workloads:
            for container in workload["spec"]["template"]["spec"]["containers"]:
                for env in container.get("env", []):
                    with self.subTest(container=container["name"], env=env["name"]):
                        if re.search(r"PASSWORD|SECRET|TOKEN", env["name"]):
                            self.assertIn("secretKeyRef", env.get("valueFrom", {}))

    def test_database_credentials_are_service_specific(self):
        refs = {}
        for workload in self.workloads:
            for container in workload["spec"]["template"]["spec"]["containers"]:
                for env in container.get("env", []):
                    ref = env.get("valueFrom", {}).get("secretKeyRef")
                    if ref:
                        refs.setdefault(workload["metadata"]["name"], set()).add(ref["name"])
        self.assertNotIn("gateway", refs, "gateway has no database")
        self.assertNotIn("storefront", refs)
        self.assertEqual(refs["catalog"], {"catalog-db-runtime", "catalog-db-migration"})

    def test_default_deny_network_policy(self):
        policies = {p["metadata"]["name"]: p["spec"] for p in self.kinds("NetworkPolicy")}
        deny = policies["default-deny-ingress"]
        self.assertEqual(deny["podSelector"], {})
        self.assertEqual(deny["policyTypes"], ["Ingress"])
        self.assertNotIn("ingress", deny)


class RenderScriptTest(unittest.TestCase):
    def run_render(self, *args):
        bash = shutil.which("bash")
        if bash is None or shutil.which("kubectl") is None:
            self.skipTest("bash and kubectl are required")
        return subprocess.run([bash, (ROOT / "scripts/render-staging.sh").as_posix(), *args],
                              capture_output=True, text=True)

    def test_render_prints_manifests(self):
        result = self.run_render()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("kind: Deployment", result.stdout)

    def test_strict_rejects_placeholder_digests_and_registry(self):
        result = self.run_render("--strict")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("placeholder", result.stderr)


class PostgresInitCopyTest(unittest.TestCase):
    def test_staging_init_script_matches_local(self):
        local = (ROOT / "infra/local/postgres-init/01-catalog-roles.sh").read_bytes()
        staging = (ROOT / "infra/environments/base/postgres-init/01-catalog-roles.sh").read_bytes()
        self.assertEqual(staging, local, "update both copies together")


class ArgoCdScopeTest(unittest.TestCase):
    def test_project_is_limited_to_this_repo_and_namespace(self):
        project = yaml.safe_load((ROOT / "infra/argocd/staging-project.yaml").read_text(encoding="utf-8"))
        spec = project["spec"]
        self.assertEqual(spec["sourceRepos"], ["https://github.com/trunghieunef/fashion-ecommerce-platform.git"])
        self.assertEqual([d["namespace"] for d in spec["destinations"]], ["fashion-staging"])
        self.assertEqual(spec["clusterResourceWhitelist"], [{"group": "", "kind": "Namespace"}])

    def test_application_tracks_the_staging_overlay_on_main(self):
        app = yaml.safe_load((ROOT / "infra/argocd/staging-application.yaml").read_text(encoding="utf-8"))
        source = app["spec"]["source"]
        self.assertEqual((source["path"], source["targetRevision"]), ("infra/environments/staging", "main"))
        self.assertEqual(app["spec"]["project"], "fashion-staging")


if __name__ == "__main__":
    unittest.main()
