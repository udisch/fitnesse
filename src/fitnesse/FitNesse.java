// Copyright (C) 2003-2009 by Object Mentor, Inc. All rights reserved.
// Released under the terms of the CPL Common Public License version 1.0.
package fitnesse;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import fitnesse.http.MockRequestBuilder;
import fitnesse.http.MockResponseSender;
import fitnesse.http.Request;
import fitnesse.http.Response;
import fitnesse.socketservice.SocketFactory;
import fitnesse.socketservice.SocketService;
import fitnesse.util.MockSocket;
import fitnesse.util.SerialExecutorService;

public class FitNesse {
  private static final Logger LOG = Logger.getLogger(FitNesse.class.getName());
  private final FitNesseContext context;
  private boolean makeDirs = true;
  private volatile SocketService theService;
  private ExecutorService executorService;

  public FitNesse(FitNesseContext context) {
    this.context = context;
    RejectedExecutionHandler rejectionHandler = new RejectedExecutionHandler() {
      @Override
      public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        LOG.log(Level.WARNING, "Could not handle request. Thread pool is exhausted.");
      }
    };
    this.executorService = new ThreadPoolExecutor(5, 100, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2), rejectionHandler);
  }

  public FitNesse dontMakeDirs() {
    makeDirs = false;
    return this;
  }

  private void establishRequiredDirectories() {
    establishDirectory(context.getRootPagePath());
    establishDirectory(context.getRootPagePath() + "/files");
  }

  private static void establishDirectory(String path) {
    File filesDir = new File(path);
    if (!filesDir.exists())
      filesDir.mkdir();
  }

  public boolean start() {
    if (makeDirs) {
      establishRequiredDirectories();
    }
    try {
      if (context.port > 0) {
        ServerSocket serverSocket = context.useHTTPS
                ? SocketFactory.createSslServerSocket(context.port, context.sslClientAuth, context.sslParameterClassName)
                : SocketFactory.createServerSocket(context.port);
        theService = new SocketService(new FitNesseServer(context, executorService), false, serverSocket);
      }
      return true;
    } catch (BindException e) {
      LOG.severe("FitNesse cannot be started...");
      LOG.severe("Port " + context.port + " is already in use.");
      LOG.severe("Use the -p <port#> command line argument to use a different port.");
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Error while starting the FitNesse socket service", e);
    }
    return false;
  }

  public void stop() throws IOException {
    if (theService != null) {
      theService.close();
      theService = null;
    }
  }

  public boolean isRunning() {
    return theService != null;
  }

  public void executeSingleCommand(String command, OutputStream out) throws Exception {
    Request request = new MockRequestBuilder(command).noChunk().build();
    FitNesseExpediter expediter = new FitNesseExpediter(new MockSocket(), context, new SerialExecutorService());
    Response response = expediter.createGoodResponse(request);
    if (response.getStatus() != 200){
        throw new Exception("error loading page: " + response.getStatus());
    }
    response.withoutHttpHeaders();
    MockResponseSender sender = new MockResponseSender(out);
    sender.doSending(response);
  }
}
