package com.merxury.blocker.core.controllers.shizuku

import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.concurrent.thread

class ShizukuUtil {
    interface OutputCallback {
        fun onOutput(line: String)
        fun onComplete()
        fun onError(e: Exception)
    }

    companion object {

    }

    private val TAG = "ShizukuUtil"
    private var p: Process? = null
    fun getTopPackageName(): String {
        return execCmd("dumpsys activity activities | grep topResumedActivity= | tail -n 1 | cut -d \"{\" -f2 | cut -d \"/\" -f1 | cut -d \" \" -f3")
            ?: ""
    }



    fun enableAccessibilityByServiceName(serviceName: String) {
        val getAccessibilityServices = getAccessibilityServices()
        if (getAccessibilityServices.contains(serviceName)) {
            return
        }
        execCmd("settings put secure enabled_accessibility_services " +
                "$getAccessibilityServices${if (getAccessibilityServices.isEmpty()||getAccessibilityServices=="null") "" else ":"}${serviceName}")
    }



    fun disableAccessibilityByService(serviceName: String) {
        val getAccessibilityServices = getAccessibilityServices()
        if (!getAccessibilityServices.contains(serviceName)) {
            return
        }
        execCmd(
            "settings put secure enabled_accessibility_services ${
                if (getAccessibilityServices.contains(":$serviceName")) getAccessibilityServices.replace(
                    ":$serviceName",
                    ""
                ) else getAccessibilityServices.replace(
                    serviceName,
                    ""
                ) 
            }"
        )
    }


    fun getAccessibilityServices(): String {
        val services = execCmd("settings get secure enabled_accessibility_services")
        return if (services == null) "" else services
    }

    private var process: Process? = null
    private var outputStream: OutputStream? = null
    private var inputReader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private var errorReadingThread: Thread? = null
    private var isShellInitialized = false

    /**
     * 初始化Shell会话（创建一个持久的shell进程）
     */
    private val initializationLock = Any()

    fun initShell(): Boolean {
        // 双重检查锁
        if (isShellInitialized) {
            return true
        }

        synchronized(initializationLock) {
            if (isShellInitialized) {
                return true
            }

            return try {
                // 检查权限
                if (!hasPermission()) {
                    Log.e(TAG, "No Shizuku or Dhizuku permission")
                    return false
                }

                // 创建新的shell进程
                process = createShellProcess()
                if (process == null) {
                    Log.e(TAG, "Failed to create shell process")
                    return false
                }

                // 获取流
                outputStream = process!!.outputStream
                inputReader = BufferedReader(InputStreamReader(process!!.inputStream))
                errorReader = BufferedReader(InputStreamReader(process!!.errorStream))

                // 启动错误流读取线程
                startErrorReaderThread()

                isShellInitialized = true
                Log.d(TAG, "Shell initialized successfully")  // ← 这里只会在真正初始化时打印
                true
            } catch (e: Exception) {
                Log.e(TAG, "initShell error: ${e.message}")
                destroyShell()
                false
            }
        }
    }

    /**
     * 销毁shell进程和所有相关资源
     */
    fun destroyShell() {
        try {
            // 停止错误读取线程
            errorReadingThread?.interrupt()
            errorReadingThread = null

            // 发送退出命令
            try {
                outputStream?.write("exit\n".toByteArray())
                outputStream?.flush()
            } catch (e: IOException) {
                // 忽略
            }

            // 关闭所有流
            outputStream?.close()
            inputReader?.close()
            errorReader?.close()

            // 销毁进程
            process?.destroy()

            // 清理引用
            outputStream = null
            inputReader = null
            errorReader = null
            process = null
            isShellInitialized = false

            Log.d(TAG, "Shell destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "destroyShell error: ${e.message}")
        }
    }

    /**
     * 检查shell是否已初始化
     */
    fun isShellReady(): Boolean {
        return isShellInitialized
    }

    // 私有方法

    private fun hasPermission(): Boolean {
        return true
    }

    private fun createShellProcess(): Process? {
        return try {
            if (hasPermission()) {
                Shizuku.newProcess(arrayOf("sh"), null, null)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "createShellProcess error: ${e.message}")
            null
        }
    }

    private fun startErrorReaderThread() {
        errorReadingThread = Thread {
            try {
                var line: String?
                while (!Thread.currentThread().isInterrupted) {
                    if (errorReader?.ready() == true) {
                        line = errorReader?.readLine()
                        if (line != null) {
                            Log.w(TAG, "Shell stderr: $line")
                        }
                    } else {
                        Thread.sleep(50)
                    }
                }
            } catch (e: InterruptedException) {
                // 线程被中断，正常退出
                Thread.currentThread().interrupt()
            } catch (e: IOException) {
                // 流关闭
                Log.d(TAG, "Error reader thread stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error reader thread error: ${e.message}")
            }
        }

        errorReadingThread?.isDaemon = true
        errorReadingThread?.start()
    }

    /**
     * 执行命令（在已初始化的shell中）
     */
    fun execCmd(cmd: String): String? {
        // 如果没有初始化，尝试初始化
        if (!isShellInitialized) {
            val initResult = initShell()
            if (!initResult) {
                return null
            }
        }

        if (outputStream == null || inputReader == null) {
            return null
        }

        return try {
            // 写入命令
            outputStream!!.write("$cmd\n".toByteArray())
            outputStream!!.write("echo 'SHELL_EXEC_END'\n".toByteArray()) // 结束标记
            outputStream!!.flush()

            // 读取输出直到遇到结束标记
            val output = StringBuilder()
            var line: String?
            val startTime = System.currentTimeMillis()
            val timeout = 5000L // 超时时间 5 秒，可根据需要调整
            while (true) {
                if (System.currentTimeMillis() - startTime > timeout) {
//                    Log.w(TAG, "execCmd timeout for cmd: $cmd")
                    break
                }
                // 检查是否有数据可读
                line = inputReader!!.readLine()
                if (line == null) {
                    // 流结束
                    break
                } else if (line.contains("SHELL_EXEC_END")) {
                    // 遇到结束标记，停止读取
                    break
                } else {
                    // 避免添加命令回显和提示符
                    if (!line.trim().endsWith("$") &&
                        !line.trim().endsWith("#") &&
                        !line.contains("echo") &&
                        line.isNotBlank()
                    ) {
                        output.append(line).append("\n")
                    }
                }
            }
            if (output.isNotEmpty()) {
                output.toString().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "execCommand error: ${e.message}")
            e.printStackTrace()
            // 发生异常，重新初始化shell
            destroyShell()
            null
        }
    }

    private fun cleanProcessBinder(process: Process?) {
        try {
            // 反射清理 Binder 相关字段
            val clazz = process?.javaClass ?: return

            // 尝试清理可能的 Binder 字段
            val fields = clazz.declaredFields
            for (field in fields) {
                if (field.type.name.contains("Binder") ||
                    field.type.name.contains("IBinder") ||
                    field.name.contains("binder") ||
                    field.name.contains("Binder")
                ) {
                    field.isAccessible = true
                    try {
                        field.set(process, null)
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略反射异常
        }
    }



    fun forceStopApp(packageName: String) {
        execCmd("am force-stop $packageName")
    }

    // 卸载应用
    fun uninstallApp(packageName: String) {
        execCmd("pm uninstall --user 0 $packageName")
    }

    // 恢复应用
    fun restoreApp(packageName: String) {
        execCmd("cmd package install-existing $packageName")
    }

    fun getAppPath (packageName: String) : String {
       return execCmd("dumpsys package ${packageName} | grep path:")?.split("path: ")[1]?:""
    }

    // 清空应用数据
    fun clearAppData(packageName: String) {
        execCmd("pm clear $packageName")
    }

    // 安装应用
    fun installApp(packageName: String) {
        execCmd("cmd package install-existing $packageName")
    }



}