#!/usr/bin/env python3
"""Waits for an uploaded build to finish processing in App Store Connect, then submits it for Beta App Review (external TestFlight testers).

Environment: ASC_KEY_PATH (the .p8), ASC_KEY_ID, ASC_ISSUER_ID, APP_APPLE_ID, MARKETING_VERSION, BUILD_NUMBER.
Never prints the key or the token. Exit 0 whatever Apple answers (the upload itself already succeeded); it prints what happened and writes
an ::error:: or ::notice:: annotation. UNVERIFIED against the live API on the first run: the endpoints are the documented v1 ones
(GET /v1/builds, POST /v1/betaAppReviewSubmissions) and the response shapes are read defensively.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt  # PyJWT with the cryptography package, installed by the workflow

API = "https://api.appstoreconnect.apple.com"


def token():
    with open(os.environ["ASC_KEY_PATH"], "rb") as f:
        key = f.read()
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"},
        key,
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"},
    )


def call(method, path, body=None):
    req = urllib.request.Request(
        API + path,
        data=None if body is None else json.dumps(body).encode(),
        method=method,
        headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw.decode("utf-8", "replace")[:500]}


def notice(kind, title, message):
    print(f"::{kind} title={title}::{message}")


def find_build(app_id, version, build):
    query = urllib.parse.urlencode({
        "filter[app]": app_id,
        "filter[version]": build,
        "filter[preReleaseVersion.version]": version,
        "limit": "5",
    })
    status, data = call("GET", "/v1/builds?" + query)
    if status != 200:
        return status, None, data
    items = data.get("data", [])
    return status, (items[0] if items else None), data


def main():
    app_id = os.environ["APP_APPLE_ID"]
    version = os.environ["MARKETING_VERSION"]
    build = os.environ["BUILD_NUMBER"]
    deadline = time.time() + 40 * 60
    build_obj = None
    while time.time() < deadline:
        status, build_obj, data = find_build(app_id, version, build)
        if status != 200:
            notice("error", "Beta review: could not list builds", f"HTTP {status}: {json.dumps(data)[:300]}")
            return
        if build_obj:
            state = build_obj.get("attributes", {}).get("processingState")
            print(f"build {version} ({build}) id={build_obj['id']} processingState={state}")
            if state == "VALID":
                break
            if state in ("FAILED", "INVALID"):
                notice("error", "Beta review: build did not process", f"Apple reports processingState={state}. Check the email from App Store Connect.")
                return
        else:
            print(f"build {version} ({build}) not listed yet")
        time.sleep(60)
    else:
        notice("warning", "Beta review: timed out", "The build was still processing after 40 minutes; submit it for Beta App Review by hand in TestFlight.")
        return

    status, data = call("POST", "/v1/betaAppReviewSubmissions", {
        "data": {
            "type": "betaAppReviewSubmissions",
            "relationships": {"build": {"data": {"type": "builds", "id": build_obj["id"]}}},
        }
    })
    if status in (200, 201):
        state = data.get("data", {}).get("attributes", {}).get("betaReviewState")
        notice("notice", "Beta review submitted", f"Build {version} ({build}) submitted for Beta App Review, state {state}.")
        return
    errors = data.get("errors") or []
    detail = "; ".join((e.get("detail") or e.get("title") or "") for e in errors) or json.dumps(data)[:300]
    # 409 is how the API says a submission already exists or the build needs no review; either is fine for the testers.
    kind = "notice" if status == 409 else "warning"
    notice(kind, "Beta review: not submitted", f"HTTP {status}: {detail[:400]}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # never fail the job because of this optional step
        notice("warning", "Beta review step crashed", f"{type(exc).__name__}: {str(exc)[:300]}")
    sys.exit(0)
