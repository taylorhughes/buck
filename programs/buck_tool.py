from __future__ import print_function
import errno
import json
import os
import platform
import re
import shlex
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import traceback
import uuid

from pynailgun import NailgunConnection, NailgunException
from timing import monotonic_time_nanos
from tracing import Tracing
from subprocutils import check_output, CalledProcessError, which
from sys import platform as os_platform

BUCKD_CLIENT_TIMEOUT_MILLIS = 60000
GC_MAX_PAUSE_TARGET = 15000

JAVA_MAX_HEAP_SIZE_MB = 1000

# While waiting for the daemon to terminate, print a message at most
# every DAEMON_BUSY_MESSAGE_SECONDS seconds.
DAEMON_BUSY_MESSAGE_SECONDS = 1.0

# Describes a resource used by this driver.
#  - name: logical name of the resources
#  - executable: whether the resource should/needs execute permissions
#  - basename: required basename of the resource
class Resource(object):
    def __init__(self, name, executable=False, basename=None):
        self.name = name
        self.executable = executable
        self.basename = name if basename is None else basename


# Resource that get propagated to buck via system properties.
EXPORTED_RESOURCES = [
    Resource("testrunner_classes"),
    Resource("abi_processor_classes"),
    Resource("path_to_asm_jar"),
    Resource("logging_config_file"),
    Resource("path_to_rawmanifest_py", basename='rawmanifest.py'),
    Resource("path_to_pathlib_py", basename='pathlib.py'),
    Resource("path_to_intellij_py"),
    Resource("path_to_pex"),
    Resource("path_to_pywatchman"),
    Resource("path_to_typing"),
    Resource("path_to_sh_binary_template"),
    Resource("jacoco_agent_jar"),
    Resource("report_generator_jar"),
    Resource("path_to_static_content"),
    Resource("path_to_pex", executable=True),
    Resource("dx"),
    Resource("android_agent_path"),
    Resource("buck_build_type_info"),
    Resource("native_exopackage_fake_path"),
]


class CommandLineArgs:
    def __init__(self, cmdline):
        self.args = cmdline[1:]

        self.buck_options = []
        self.command = None
        self.command_options = []

        for arg in self.args:
            if (self.command is not None):
                self.command_options.append(arg)
            elif (arg[:1]) == "-":
                self.buck_options.append(arg)
            else:
                self.command = arg

    # Whether this is a help command that doesn't run a build
    # n.b. 'buck --help clean' is *not* currently a help command
    # n.b. 'buck --version' *is* a help command
    def is_help(self):
        return self.command is None or "--help" in self.command_options


class RestartBuck(Exception):
    pass


class BuckToolException(Exception):
    pass


class BuckTool(object):

    def __init__(self, buck_project):
        self._init_timestamp = int(round(time.time() * 1000))
        self._command_line = CommandLineArgs(sys.argv)
        self._buck_project = buck_project
        self._tmp_dir = self._platform_path(buck_project.tmp_dir)
        self._stdout_file = os.path.join(self._tmp_dir, "stdout")
        self._stderr_file = os.path.join(self._tmp_dir, "stderr")

        self._pathsep = os.pathsep
        if (sys.platform == 'cygwin'):
            self._pathsep = ';'

    # Check whether the given resource exists.
    def _has_resource(self, resource):
        raise NotImplementedError()

    # Return an on-disk path to the given resource.  This may cause
    # implementations to unpack the resource at this point.
    def _get_resource(self, resource):
        raise NotImplementedError()

    # Return the path to the file used to determine if another buck process is using the same
    # resources folder (used for cleanup coordination).
    def _get_resource_lock_path(self):
        raise NotImplementedError()

    def _use_buckd(self):
        return not os.environ.get('NO_BUCKD')

    def _environ_for_buck(self):
        env = os.environ.copy()
        env['CLASSPATH'] = str(self._get_bootstrap_classpath())
        env['BUCK_CLASSPATH'] = str(self._get_java_classpath())
        env['BUCK_TTY'] = str(int(sys.stdin.isatty()))
        return env

    def launch_buck(self, build_id):
        with Tracing('BuckTool.launch_buck'):
            if self._command_line.command == "clean" and not self._command_line.is_help():
                self.kill_buckd()

            buck_version_uid = self._get_buck_version_uid()

            use_buckd = self._use_buckd()
            if not self._command_line.is_help():
                has_watchman = bool(which('watchman'))
                if use_buckd and has_watchman:
                    running_version = self._buck_project.get_running_buckd_version()

                    if (running_version != buck_version_uid):
                        self.kill_buckd()

                    if not self._is_buckd_running():
                        self.launch_buckd(buck_version_uid=buck_version_uid)
                elif use_buckd and not has_watchman:
                    print("Not using buckd because watchman isn't installed.",
                          file=sys.stderr)
                elif not use_buckd:
                    print("Not using buckd because NO_BUCKD is set.",
                          file=sys.stderr)

            env = self._environ_for_buck()
            env['BUCK_BUILD_ID'] = build_id

            buck_socket_path = self._buck_project.get_buckd_socket_path()

            if use_buckd and self._is_buckd_running() and \
                    os.path.exists(buck_socket_path):
                with Tracing('buck', args={'command': sys.argv[1:]}):
                    exit_code = 2
                    last_diagnostic_time = 0
                    while exit_code == 2:
                        with NailgunConnection('local:.buckd/sock',
                                               cwd=self._buck_project.root) as c:
                            now = int(round(time.time() * 1000))
                            env['BUCK_PYTHON_SPACE_INIT_TIME'] = str(now - self._init_timestamp)
                            exit_code = c.send_command(
                                'com.facebook.buck.cli.Main',
                                sys.argv[1:],
                                env=env,
                                cwd=self._buck_project.root)
                            if exit_code == 2:
                                env['BUCK_BUILD_ID'] = str(uuid.uuid4())
                                now = time.time()
                                if now - last_diagnostic_time > DAEMON_BUSY_MESSAGE_SECONDS:
                                    print('Daemon is busy, waiting for it to become free...',
                                          file=sys.stderr)
                                    last_diagnostic_time = now
                                time.sleep(1)
                    return exit_code


            command = ["buck"]
            extra_default_options = [
                "-Djava.io.tmpdir={0}".format(self._tmp_dir)
            ]
            command.extend(self._get_java_args(buck_version_uid, extra_default_options))
            command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
            command.append("com.facebook.buck.cli.Main")
            command.extend(sys.argv[1:])

            now = int(round(time.time() * 1000))
            env['BUCK_PYTHON_SPACE_INIT_TIME'] = str(now - self._init_timestamp)
            if True:
                java = which("java")
                if java is None:
                    raise BuckToolException('Could not find java on $PATH')
                with Tracing('buck', args={'command': command}):
                    buck_exit_code = subprocess.call(command,
                                                     cwd=self._buck_project.root,
                                                     env=env,
                                                     executable=java)
            return buck_exit_code


    def _truncate_logs_pretty(self, logs):
        NUMBER_OF_LINES_BEFORE = 100
        NUMBER_OF_LINES_AFTER = 100
        if len(logs) <= NUMBER_OF_LINES_BEFORE + NUMBER_OF_LINES_AFTER:
            return logs
        new_logs = logs[:NUMBER_OF_LINES_BEFORE]
        new_logs.append('...<truncated>...')
        new_logs.extend(logs[-NUMBER_OF_LINES_AFTER:])
        return new_logs

    def _generate_log_entry(self, exit_code, message, logs_array):
        import socket
        import getpass
        traits = {
            "severity": "SEVERE",
            "logger": "com.facebook.buck.python.buck_tool.py",
            "buckGitCommit": self._get_buck_version_uid(),
            "os": platform.system(),
            "osVersion": platform.release(),
            "user": getpass.getuser(),
            "hostname": socket.gethostname(),
            "isSuperConsoleEnabled": "false",
            "isDaemon": "false",
        }
        entry = {
          "logs": logs_array,
          "traits": traits,
          "message": message,
          "category": message,
          "time": int(time.time()),
          "logger": "com.facebook.buck.python.buck_tool.py",
        }
        return entry

    def launch_buckd(self, buck_version_uid=None):
        with Tracing('BuckTool.launch_buckd'):
            self._setup_watchman_watch()
            if buck_version_uid is None:
                buck_version_uid = self._get_buck_version_uid()
            # Override self._tmp_dir to a long lived directory.
            buckd_tmp_dir = self._buck_project.create_buckd_tmp_dir()
            ngserver_output_path = os.path.join(buckd_tmp_dir, 'ngserver-out')

            '''
            Use SoftRefLRUPolicyMSPerMB for immediate GC of javac output.
            Set timeout to 60s (longer than the biggest GC pause seen for a 2GB
            heap) and GC target to 15s. This means that the GC has to miss its
            target by 100% or many 500ms heartbeats must be missed before a client
            disconnection occurs. Specify port 0 to allow Nailgun to find an
            available port, then parse the port number out of the first log entry.
            '''
            command = ["buckd"]
            extra_default_options = [
                "-Dbuck.buckd_launch_time_nanos={0}".format(monotonic_time_nanos()),
                "-XX:MaxGCPauseMillis={0}".format(GC_MAX_PAUSE_TARGET),
                "-XX:SoftRefLRUPolicyMSPerMB=0",
                # Stop Java waking up every 50ms to collect thread
                # statistics; doing it once every five seconds is much
                # saner for a long-lived daemon.
                "-XX:PerfDataSamplingInterval=5000",
                # Do not touch most signals
                "-Xrs",
                # Likewise, waking up once per second just in case
                # there's some rebalancing to be done is silly.
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:GuaranteedSafepointInterval=5000",
                "-Djava.io.tmpdir={0}".format(buckd_tmp_dir),
                "-Dcom.martiansoftware.nailgun.NGServer.outputPath={0}".format(
                    ngserver_output_path),
                "-XX:+UseG1GC",
                "-XX:MaxHeapFreeRatio=40",
            ]

            command.extend(self._get_java_args(buck_version_uid, extra_default_options))
            command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
            command.append("com.facebook.buck.cli.Main$DaemonBootstrap")
            command.append("local:.buckd/sock")
            command.append("{0}".format(BUCKD_CLIENT_TIMEOUT_MILLIS))

            '''
            Change the process group of the child buckd process so that when this
            script is interrupted, it does not kill buckd.
            '''
            def preexec_func():
                # Close any open file descriptors to further separate buckd from its
                # invoking context (e.g. otherwise we'd hang when running things like
                # `ssh localhost buck clean`).
                dev_null_fd = os.open("/dev/null", os.O_RDWR)
                os.dup2(dev_null_fd, 0)
                os.dup2(dev_null_fd, 1)
                os.dup2(dev_null_fd, 2)
                os.close(dev_null_fd)
            buck_socket_path = self._buck_project.get_buckd_socket_path()

            # Make sure the Unix domain socket doesn't exist before this call.
            try:
                os.unlink(buck_socket_path)
            except OSError as e:
                if e.errno == errno.ENOENT:
                    # Socket didn't previously exist.
                    pass
                else:
                    raise e

            process = subprocess.Popen(
                command,
                executable=which("java"),
                cwd=self._buck_project.root,
                close_fds=True,
                preexec_fn=preexec_func,
                env=self._environ_for_buck())

            self._buck_project.save_buckd_version(buck_version_uid)

            # Give Java some time to create the listening socket.
            for i in range(0, 300):
                if not os.path.exists(buck_socket_path):
                    time.sleep(0.01)

            returncode = process.poll()

            # If the process hasn't exited yet, everything is working as expected
            if returncode is None:
                return 0

            return returncode

    def kill_buckd(self):
        with Tracing('BuckTool.kill_buckd'):
            buckd_socket_path = self._buck_project.get_buckd_socket_path()
            if os.path.exists(buckd_socket_path):
                print("Shutting down nailgun server...", file=sys.stderr)
                try:
                    with NailgunConnection('local:.buckd/sock', cwd=self._buck_project.root) as c:
                        c.send_command('ng-stop')
                except NailgunException as e:
                    if e.code not in (NailgunException.CONNECT_FAILED,
                                      NailgunException.CONNECTION_BROKEN,
                                      NailgunException.UNEXPECTED_CHUNKTYPE):
                        raise BuckToolException(
                            'Unexpected error shutting down nailgun server: ' +
                            str(e))

            self._buck_project.clean_up_buckd()

    def _setup_watchman_watch(self):
        with Tracing('BuckTool._setup_watchman_watch'):
            if not which('watchman'):
                message = textwrap.dedent("""\
                    Watchman not found, please install when using buckd.
                    See https://github.com/facebook/watchman for details.""")
                if sys.platform == "darwin":
                    message += "\n(brew install watchman on OS X)"
                # Bail if watchman isn't installed as we know java's
                # FileSystemWatcher will take too long to process events.
                raise BuckToolException(message)

            print("Using watchman.", file=sys.stderr)

    def _is_buckd_running(self):
        with Tracing('BuckTool._is_buckd_running'):
            buckd_socket_path = self._buck_project.get_buckd_socket_path()

            if not os.path.exists(buckd_socket_path):
                return False

            try:
                with NailgunConnection(
                        'local:.buckd/sock',
                        stdin=None,
                        stdout=None,
                        stderr=None,
                        cwd=self._buck_project.root) as c:
                    c.send_command('ng-stats')
            except NailgunException as e:
                if e.code == NailgunException.CONNECT_FAILED:
                    return False
                else:
                    raise
            return True

    def _get_buck_version_uid(self):
        raise NotImplementedError()

    def _get_bootstrap_classpath(self):
        raise NotImplementedError()

    def _get_java_classpath(self):
        raise NotImplementedError()

    def _is_buck_production(self):
        raise NotImplementedError()

    def _get_extra_java_args(self):
        return []

    def _get_java_args(self, version_uid, extra_default_options=[]):
        with Tracing('BuckTool._get_java_args'):
            java_args = [
                "-Xmx{0}m".format(JAVA_MAX_HEAP_SIZE_MB),
                "-Djava.awt.headless=true",
                "-Djava.util.logging.config.class=com.facebook.buck.cli.bootstrapper.LogConfig",
                "-Dbuck.test_util_no_tests_dir=true",
                "-Dbuck.version_uid={0}".format(version_uid),
                "-Dbuck.buckd_dir={0}".format(self._buck_project.buckd_dir),
                "-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog",
            ]

            resource_lock_path = self._get_resource_lock_path()
            if resource_lock_path is not None:
                java_args.append("-Dbuck.resource_lock_path={0}".format(resource_lock_path))

            for resource in EXPORTED_RESOURCES:
                if self._has_resource(resource):
                    java_args.append(
                        "-Dbuck.{0}={1}".format(
                            resource.name, self._get_resource(resource)))

            if sys.platform == "darwin":
                java_args.append("-Dbuck.enable_objc=true")
                java_args.append("-Djava.library.path=" + os.path.dirname(
                    self._get_resource(
                        Resource("libjcocoa.dylib"))))

            if os.environ.get("BUCK_DEBUG_MODE"):
                java_args.append("-agentlib:jdwp=transport=dt_socket,"
                                 "server=y,suspend=y,address=8888")

            if os.environ.get("BUCK_DEBUG_SOY"):
                java_args.append("-Dbuck.soy.debug=true")

            java_args.extend(extra_default_options)

            if self._buck_project.buck_javaargs:
                java_args.extend(shlex.split(self._buck_project.buck_javaargs))

            if self._buck_project.buck_javaargs_local:
                java_args.extend(shlex.split(self._buck_project.buck_javaargs_local))

            java_args.extend(self._get_extra_java_args())

            extra_java_args = os.environ.get("BUCK_EXTRA_JAVA_ARGS")
            if extra_java_args:
                java_args.extend(shlex.split(extra_java_args))
            return java_args

    def _platform_path(self, path):
        if sys.platform != 'cygwin':
            return path
        return subprocess.check_output(['cygpath', '-w', path]).strip()


def install_signal_handlers():
    if os.name == 'posix':
        signal.signal(
            signal.SIGUSR1,
            lambda sig, frame: traceback.print_stack(frame))
