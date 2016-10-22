/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package restore.sql.tail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.execution.TaskExecutor;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.process.ProcessWaitFor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Consumer;
import com.intellij.util.io.BaseDataReader;
import com.intellij.util.io.BaseInputStreamReader;
import com.intellij.util.io.BaseOutputReader;

import static com.intellij.util.io.BaseDataReader.AdaptiveSleepingPolicy;

/*from com.intellij.execution.process.BaseOSProcessHandler*/
public class MyProcessHandler extends ProcessHandler implements TaskExecutor {
  private static final Logger LOG = Logger.getInstance(MyProcessHandler.class);

  protected final Process myProcess;
  protected final Charset myCharset;
  protected final String myPresentableName;
  protected final ProcessWaitFor myWaitFor;

  /**
   * {@code commandLine} must not be not empty (for correct thread attribution in the stacktrace)
   */
  public MyProcessHandler(@NotNull Process process, /*@NotNull*/ String commandLine, @Nullable Charset charset) {
    myProcess = process;
    myCharset = charset;
    myPresentableName = commandLine;
    myWaitFor = new ProcessWaitFor(process, this);
  }

  /**
   * Override this method in order to execute the task with a custom pool
   *
   * @param task a task to run
   */
  @NotNull
  protected Future<?> executeOnPooledThread(@NotNull Runnable task) {
    return ExecutorServiceHolder.ourThreadExecutorsService.submit(task);
  }

  @Override
  @NotNull
  public Future<?> executeTask(@NotNull Runnable task) {
    return executeOnPooledThread(task);
  }

  @NotNull
  public Process getProcess() {
    return myProcess;
  }

  protected boolean useAdaptiveSleepingPolicyWhenReadingOutput() {
    return false;
  }

  /**
   * Override this method to read process output and error streams in blocking mode
   *
   * @return true to read non-blocking but sleeping, false for blocking read
   */
  protected boolean useNonBlockingRead() {
    return !Registry.is("output.reader.blocking.mode", false);
  }

  protected boolean processHasSeparateErrorStream() {
    return true;
  }

  @Override
  public void startNotify() {
    addProcessListener(new ProcessAdapter() {
      @Override
      public void startNotified(final ProcessEvent event) {
        try {
          final BaseDataReader stdOutReader = createOutputDataReader(getPolicy());
          final BaseDataReader stdErrReader = processHasSeparateErrorStream() ? createErrorDataReader(getPolicy()) : null;

          myWaitFor.setTerminationCallback(new Consumer<Integer>() {
            @Override
            public void consume(Integer exitCode) {
              try {
                // tell readers that no more attempts to read process' output should be made
                if (stdErrReader != null) stdErrReader.stop();
                stdOutReader.stop();

                try {
                  if (stdErrReader != null) stdErrReader.waitFor();
                  stdOutReader.waitFor();
                }
                catch (InterruptedException ignore) { }
              }
              finally {
                onOSProcessTerminated(exitCode);
              }
            }
          });
        }
        finally {
          removeProcessListener(this);
        }
      }
    });

    super.startNotify();
  }

  @NotNull
  private BaseDataReader.SleepingPolicy getPolicy() {
    if (useNonBlockingRead()) {
      return useAdaptiveSleepingPolicyWhenReadingOutput() ? new AdaptiveSleepingPolicy() : BaseDataReader.SleepingPolicy.SIMPLE;
    }
    else {
      //use blocking read policy
      return BaseDataReader.SleepingPolicy.BLOCKING;
    }
  }

  @NotNull
  protected BaseDataReader createErrorDataReader(@NotNull BaseDataReader.SleepingPolicy sleepingPolicy) {
    return new SimpleOutputReader(createProcessErrReader(), ProcessOutputTypes.STDERR, sleepingPolicy, "error stream of " + myPresentableName);
  }

  @NotNull
  protected BaseDataReader createOutputDataReader(@NotNull BaseDataReader.SleepingPolicy sleepingPolicy) {
    return new SimpleOutputReader(createProcessOutReader(), ProcessOutputTypes.STDOUT, sleepingPolicy, "output stream of " + myPresentableName);
  }

  protected void onOSProcessTerminated(final int exitCode) {
    notifyProcessTerminated(exitCode);
  }

  @NotNull
  protected Reader createProcessOutReader() {
    return createInputStreamReader(myProcess.getInputStream());
  }

  @NotNull
  protected Reader createProcessErrReader() {
    return createInputStreamReader(myProcess.getErrorStream());
  }

  @NotNull
  private Reader createInputStreamReader(@NotNull InputStream streamToRead) {
    Charset charset = charsetNotNull();
    return new BaseInputStreamReader(streamToRead, charset);
  }

  @NotNull
  private Charset charsetNotNull() {
    Charset charset = getCharset();
    if (charset == null) {
      // use default charset
      charset = Charset.defaultCharset();
    }
    return charset;
  }

  @Override
  protected void destroyProcessImpl() {
    try {
      closeStreams();
    }
    finally {
      doDestroyProcess();
    }
  }

  protected void doDestroyProcess() {
    getProcess().destroy();
  }

  @Override
  protected void detachProcessImpl() {
    final Runnable runnable = new Runnable() {
      @Override
      public void run() {
        closeStreams();

        myWaitFor.detach();
        notifyProcessDetached();
      }
    };

    executeOnPooledThread(runnable);
  }

  protected void closeStreams() {
    try {
      myProcess.getOutputStream().close();
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }

  @Override
  public boolean detachIsDefault() {
    return false;
  }

  @Override
  public OutputStream getProcessInput() {
    return myProcess.getOutputStream();
  }

  @Nullable
  public Charset getCharset() {
    return myCharset;
  }

  public static class ExecutorServiceHolder {
    private static final ThreadPoolExecutor ourThreadExecutorsService =
      new ThreadPoolExecutor(0, Integer.MAX_VALUE, 1, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                             ConcurrencyUtil.newNamedThreadFactory("OSProcessHandler pooled thread"));

    /** @deprecated use {@link MyProcessHandler#submit(Runnable)} instead (to be removed in IDEA 16) */
    @Deprecated
    public static Future<?> submit(@NotNull Runnable task) {
      return MyProcessHandler.submit(task);
    }
  }

  @NotNull
  public static Future<?> submit(@NotNull Runnable task) {
    return ExecutorServiceHolder.ourThreadExecutorsService.submit(task);
  }

  private class SimpleOutputReader extends BaseOutputReader {
    private final Key myProcessOutputType;

    private SimpleOutputReader(@NotNull Reader reader, @NotNull Key processOutputType, SleepingPolicy sleepingPolicy, @NotNull String presentableName) {
      super(reader, sleepingPolicy);
      myProcessOutputType = processOutputType;
      try {
        java.lang.reflect.Method method;
        method = BaseDataReader.class.getDeclaredMethod("start", String.class);
        method.invoke(this, presentableName);
      } catch (Exception e) {
        //IJ 14,15
        start();
      }
    }

    @NotNull
    @Override
    protected Future<?> executeOnPooledThread(@NotNull Runnable runnable) {
      return MyProcessHandler.this.executeOnPooledThread(runnable);
    }

    @Override
    protected void onTextAvailable(@NotNull String text) {
      notifyTextAvailable(text, myProcessOutputType);
    }
  }

  @Override
  public String toString() {
    return myPresentableName;
  }
}