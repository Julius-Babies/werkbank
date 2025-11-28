    suspend fun initialize() {
        print(buildStyledString {
            cyan { +"Checking OpenSSL availability... " }
        })
        try {
            withContext(Dispatchers.IO) {
                Command("openssl")
                    .stdout(Stdio.Null)
                    .stderr(Stdio.Pipe)
                    .spawn()
                    .wait()
            }
            isOpensslAvailable.complete(true)
            println(buildStyledString {
                +REPLACE_LINE
                green { +"$CHECK OpenSSL is available" }
            })
        } catch (e: KommandException) {
            if (e.message?.startsWith("No such file or directory") == true) {
                println(buildStyledString {
                    +REPLACE_LINE
                    red { +"✗ OpenSSL not found" }
                })
                isOpensslAvailable.complete(false)
                return
            }
            throw e
        }

        if (!isRootCaSetUp()) createRootCa()
    }
