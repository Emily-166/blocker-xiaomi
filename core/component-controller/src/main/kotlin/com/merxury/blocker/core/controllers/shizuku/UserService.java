/*
 * Copyright 2026 Blocker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.merxury.blocker.core.controllers.shizuku;

import android.os.RemoteException;
import com.merxury.blocker.core.controller.IUserService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class UserService extends IUserService.Stub {

    @Override
    public void destroy() throws RemoteException {
        System.exit(0);
    }

    @Override
    public void exit() throws RemoteException {
        destroy();
    }

    @Override
    public String execLine(String command) throws RemoteException {
        try {
            // 执行shell命令
            Process process = Runtime.getRuntime().exec(command);
            // 读取执行结果
            return readResult(process);
        } catch (InterruptedException | IOException e) {
            throw new RemoteException();
        }
    }

    @Override
    public String execArr(String[] command) throws RemoteException {
        try {
            // 执行shell命令
            Process process = Runtime.getRuntime().exec(command);
            // 读取执行结果
            return readResult(process);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 读取执行结果，如果有异常会向上抛
     */
    public String readResult(Process process) throws IOException, InterruptedException {
        StringBuilder stringBuilder = new StringBuilder();
        // 读取执行结果
        InputStreamReader inputStreamReader = new InputStreamReader(process.getInputStream());
        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            stringBuilder.append(line).append("\n");
        }
        inputStreamReader.close();
        process.waitFor();
        return stringBuilder.toString();
    }
}
