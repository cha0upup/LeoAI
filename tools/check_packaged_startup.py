#!/usr/bin/env python3
"""Smoke-test a packaged LeoAI JAR against an isolated, rebuildable SQLite database."""

import argparse
from contextlib import closing, contextmanager
from http.client import HTTPConnection, HTTPException
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import subprocess
import sys
from tempfile import TemporaryDirectory
import time


HOST = '127.0.0.1'
STATUS_PATH = '/platform/user/status'
STARTUP_TIMEOUT = 60
TOMCAT_PORT = re.compile(r'Tomcat started on port (\d+)\b')


def java_executable():
    name = 'java.exe' if os.name == 'nt' else 'java'
    java_home = os.environ.get('JAVA_HOME')
    java = Path(java_home) / 'bin' / name if java_home else shutil.which(name)
    if not java or not Path(java).is_file():
        raise ValueError('java is required; set JAVA_HOME to the application JDK')
    return Path(java).resolve()


def api(port, path, payload=None, waiting=False):
    with closing(HTTPConnection(HOST, port, timeout=3)) as client:
        if payload is None:
            client.request('GET', path)
        else:
            client.request('POST', path, json.dumps(payload), {'Content-Type': 'application/json'})
        with client.getresponse() as response:
            if waiting and response.status == 503:
                return None
            if response.status != 200:
                raise ValueError(f'{path} returned HTTP {response.status}')
            body = json.load(response)
        if not isinstance(body, dict) or body.get('code') != 200:
            raise ValueError(f'Unexpected API result for {path}')
        return body.get('data')


def wait_until_ready(process, log):
    deadline = time.monotonic() + STARTUP_TIMEOUT
    port = None
    while time.monotonic() < deadline:
        exit_code = process.poll()
        if exit_code is not None:
            raise ValueError(f'Java exited before readiness (code {exit_code})')
        if port is None:
            match = TOMCAT_PORT.search(log.read_text(encoding='utf-8', errors='replace'))
            if match:
                port = int(match[1])
        if port:
            try:
                status = api(port, STATUS_PATH, waiting=True)
                if status is not None:
                    return port, status
            except (ConnectionError, TimeoutError):
                pass
        time.sleep(0.2)
    raise ValueError(f'Application did not become ready within {STARTUP_TIMEOUT}s')


@contextmanager
def running_application(java, jar, workspace, stage):
    log = workspace / f'{stage}.log'
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(('SPRING_', 'SERVER_', 'LOGGING_', 'LEO_', 'SESSION_'))
                   and key.upper() not in {'VFSPATH', 'VFS_PATH', 'JAVA_TOOL_OPTIONS',
                                           'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
    command = [java, '--add-opens', 'java.base/java.lang=ALL-UNNAMED', f'-Duser.home={workspace}',
               '-jar', jar, f'--server.address={HOST}', '--server.port=0',
               '--spring.config.location=classpath:/application.properties',
               '--spring.datasource.url=jdbc:sqlite:data.db', '--VfsPath=root']
    with log.open('wb') as output:
        process = subprocess.Popen(command, cwd=workspace, env=environment,
                                   stdout=output, stderr=subprocess.STDOUT)
        try:
            yield wait_until_ready(process, log)
        except (OSError, HTTPException, ValueError) as error:
            tail = '\n'.join(log.read_text(encoding='utf-8', errors='replace').splitlines()[-35:])
            raise ValueError(f'{stage}: {error}\n{tail}') from error
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def verify_status(status, initialization_required, minimum_length):
    if (not isinstance(status, dict)
            or status.get('initializationRequired') is not initialization_required
            or status.get('passwordMinLength') != minimum_length):
        raise ValueError('Unexpected initialization status or password configuration')


def verify_login(port, password):
    user = api(port, '/platform/user/login', {'username': 'admin', 'password': password})
    if not isinstance(user, dict) or user.get('userId') != 'admin':
        raise ValueError('Expected the initialized administrator to log in')


def verify_startup(jar):
    jar = jar.resolve(strict=True)
    java = java_executable()
    password = secrets.token_urlsafe(24)
    with TemporaryDirectory(prefix='leo-packaged-startup-') as directory:
        workspace = Path(directory)
        with running_application(java, jar, workspace, 'first-start') as (port, status):
            verify_status(status, True, 8)
            api(port, '/platform/setup', {'password': password, 'confirmPassword': password})
            verify_login(port, password)

        with closing(sqlite3.connect(workspace / 'data.db')) as database, database:
            changed = database.execute("UPDATE system_configs SET config_value='12' "
                                       "WHERE config_key='security.password.min.length'").rowcount
            if changed != 1:
                raise ValueError('Packaged startup did not create the expected default setting')

        with running_application(java, jar, workspace, 'restart') as (port, status):
            verify_status(status, False, 12)
            verify_login(port, password)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path, help='Path to the executable Spring Boot JAR')
    args = parser.parse_args(argv)
    try:
        verify_startup(args.jar)
    except (OSError, ValueError, sqlite3.Error) as error:
        print(f'Packaged startup failed: {error}', file=sys.stderr)
        return 1
    print('Packaged startup passed: fresh setup, login and same-database restart.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
