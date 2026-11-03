package sdfx.engine

import java.io.{BufferedInputStream, BufferedWriter, OutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets.{UTF_8, UTF_16LE}
import java.nio.file.Path
import java.util.{Base64, Locale}
import java.util.concurrent.{Callable, Executors, ThreadFactory, TimeUnit, TimeoutException}

/** Restricted MCP input: verify the final path of an open handle and copy from that handle. */
private[engine] object VerifiedFolderInput:
  def ensureSupported(platform: String = System.getProperty("os.name")): Unit =
    if !platform.toLowerCase(Locale.ROOT).contains("win") then
      throw new UnsupportedOperationException("Restricted folder input requires verified Windows file handles")

  private val script = """
    |$ErrorActionPreference='Stop'
    |$ProgressPreference='SilentlyContinue'
    |[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
    |[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    |Add-Type -ErrorAction Stop -TypeDefinition @'
    |using System;
    |using System.ComponentModel;
    |using System.IO;
    |using System.Runtime.InteropServices;
    |using System.Text;
    |using Microsoft.Win32.SafeHandles;
    |public static class SdfxVerifiedFolderRead {
    |  [StructLayout(LayoutKind.Sequential)]
    |  struct ATTR { public UInt32 Attributes; public UInt32 Tag; }
    |  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true, ExactSpelling=true)]
    |  static extern SafeFileHandle CreateFileW(string name, UInt32 access, UInt32 share, IntPtr security, UInt32 disposition, UInt32 flags, IntPtr template);
    |  [DllImport("kernel32.dll", SetLastError=true, ExactSpelling=true)]
    |  static extern bool GetFileInformationByHandleEx(SafeFileHandle file, Int32 kind, out ATTR value, UInt32 size);
    |  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true, ExactSpelling=true)]
    |  static extern UInt32 GetFinalPathNameByHandleW(SafeFileHandle file, StringBuilder path, UInt32 length, UInt32 flags);
    |  static string Local(string path) {
    |    if (String.IsNullOrEmpty(path) || path.Length < 3 || path.Length > 8192 ||
    |        !Char.IsLetter(path[0]) || path[1] != ':' || (path[2] != '\\' && path[2] != '/') || path.IndexOf(':', 2) >= 0)
    |      throw new IOException("Not an ordinary local drive path");
    |    string full = Path.GetFullPath(path);
    |    DriveType type = new DriveInfo(Path.GetPathRoot(full)).DriveType;
    |    if (type != DriveType.Fixed && type != DriveType.Removable && type != DriveType.Ram)
    |      throw new IOException("Not a local drive");
    |    return full;
    |  }
    |  public static FileStream Open(string input, string[] roots, long maxBytes) {
    |    string path = Local(input);
    |    if (roots == null || roots.Length == 0) throw new IOException("No allowed root");
    |    string[] allowed = Array.ConvertAll(roots, Local);
    |    SafeFileHandle handle = CreateFileW(path, 0x80000000, 7, IntPtr.Zero, 3, 0x00200000, IntPtr.Zero);
    |    if (handle.IsInvalid) { handle.Dispose(); throw new Win32Exception(Marshal.GetLastWin32Error()); }
    |    try {
    |      ATTR attr;
    |      if (!GetFileInformationByHandleEx(handle, 9, out attr, (UInt32)Marshal.SizeOf(typeof(ATTR))))
    |        throw new Win32Exception(Marshal.GetLastWin32Error());
    |      if ((attr.Attributes & 0x410) != 0) throw new IOException("Reparse point or directory");
    |      var buffer = new StringBuilder(32768);
    |      UInt32 n = GetFinalPathNameByHandleW(handle, buffer, (UInt32)buffer.Capacity, 0);
    |      if (n == 0 || n >= buffer.Capacity) throw new IOException("Final handle path unavailable");
    |      string actual = buffer.ToString();
    |      if (!actual.StartsWith(@"\\?\", StringComparison.Ordinal) || actual.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase))
    |        throw new IOException("Not an ordinary local handle");
    |      actual = Local(actual.Substring(4));
    |      bool inside = false;
    |      foreach (string root in allowed) {
    |        string prefix = root.TrimEnd('\\', '/') + "\\";
    |        if (actual.StartsWith(prefix, StringComparison.Ordinal)) { inside = true; break; }
    |      }
    |      if (!inside) throw new IOException("Final handle escaped the allowed roots");
    |      FileStream stream = new FileStream(handle, FileAccess.Read);
    |      if (stream.Length <= 0 || stream.Length > maxBytes) { stream.Dispose(); throw new IOException("Invalid input size"); }
    |      return stream;
    |    } catch { handle.Dispose(); throw; }
    |  }
    |}
    |'@
    |try {
    |  $request = ConvertFrom-Json -InputObject ([Console]::ReadLine())
    |  $stream = [SdfxVerifiedFolderRead]::Open([string]$request.path, [string[]]$request.roots, [long]$request.maxBytes)
    |  try {
    |    [Console]::WriteLine('READY ' + $stream.Length)
    |    [Console]::Out.Flush()
    |    if ([Console]::ReadLine() -ne 'COPY') { throw 'Copy not admitted' }
    |    $out = [Console]::OpenStandardOutput()
    |    $buffer = [byte[]]::new(65536)
    |    while (($count = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) { $out.Write($buffer, 0, $count) }
    |    $out.Flush()
    |  } finally { $stream.Dispose() }
    |} catch { [Console]::Error.WriteLine('Verified input unavailable'); exit 1 }
    |""".stripMargin

  def copy(path: Path, roots: Seq[Path], output: OutputStream, check: () => Unit = () => (),
      afterOpen: () => Unit = () => (), platform: String = System.getProperty("os.name")): Unit =
    ensureSupported(platform)
    require(roots.nonEmpty, "Restricted folder input requires an allowed root")
    check()
    val systemRoot = Option(System.getenv("SystemRoot")).getOrElse(throw new IllegalStateException("Windows system root unavailable"))
    val exe = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString
    val encoded = Base64.getEncoder.encodeToString(script.getBytes(UTF_16LE))
    val process = new ProcessBuilder(exe, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
      .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val outputGate = new Object
    var outputOpen = true
    val executor = Executors.newSingleThreadExecutor(new ThreadFactory:
      def newThread(r: Runnable): Thread =
        val t = new Thread(r, "verified-folder-input")
        t.setDaemon(true)
        t)
    try
      val task = executor.submit(new Callable[Unit]:
        def call(): Unit =
          val writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream, UTF_8))
          val in = new BufferedInputStream(process.getInputStream)
          writer.write(ujson.Obj("path" -> path.toAbsolutePath.normalize().toString,
            "roots" -> ujson.Arr.from(roots.map(_.toAbsolutePath.normalize().toString)),
            "maxBytes" -> ujson.Num(Img2ImgFolderManifest.MaxFileBytes.toDouble)).render())
          writer.newLine(); writer.flush()
          val header = new StringBuilder
          var c = in.read()
          while c >= 0 && c != '\n' && header.length < 128 do
            if c != '\r' then header.append(c.toChar)
            c = in.read()
          val value = header.toString
          if c != '\n' || !value.startsWith("READY ") then throw new IllegalArgumentException("Verified input open failed")
          val size = value.substring(6).toLongOption.getOrElse(throw new IllegalArgumentException("Invalid verified input size"))
          require(size > 0 && size <= Img2ImgFolderManifest.MaxFileBytes, "Invalid verified input size")
          check(); afterOpen(); check()
          writer.write("COPY\n"); writer.flush()
          var left = size
          val bytes = new Array[Byte](65536)
          while left > 0 do
            check()
            val n = in.read(bytes, 0, math.min(left, bytes.length.toLong).toInt)
            if n < 0 then throw new IllegalArgumentException("Verified input ended early")
            outputGate.synchronized {
              if !outputOpen then throw new IllegalStateException("Verified input has closed")
              output.write(bytes, 0, n)
            }
            left -= n
          check()
          if in.read() != -1 || process.waitFor() != 0 then throw new IllegalArgumentException("Verified input changed while reading")
      )
      try
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        var finished = false
        while !finished do
          check()
          if System.nanoTime() >= deadline then throw new TimeoutException()
          try
            task.get(250, TimeUnit.MILLISECONDS)
            finished = true
          catch case _: TimeoutException => ()
      catch
        case e: java.util.concurrent.ExecutionException => throw e.getCause
        case _: TimeoutException => throw new IllegalStateException("Verified input timed out")
        case e: InterruptedException =>
          Thread.currentThread().interrupt()
          throw e
    finally
      outputGate.synchronized { outputOpen = false }
      if process.isAlive then process.destroyForcibly()
      executor.shutdownNow()
