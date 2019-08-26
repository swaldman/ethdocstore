# ethdocstore

This application contains:

1. A smart contract that stores names, descriptions, and hashes of documents on the Ethereum blockchain
2. A web application that stores and serves those hashed documents, and optionally keeps them synced to a git repository
2. An [sbt-ethereum](http://www.sbt-ethereum.io/)-based CLI to manage the smart contract and associated documents, including
   - defining Ethereum addresses authorized to upload documents and modify their names and descriptions
   - defining associations between web-application username / password pairs and authorized Ethereum addresses
   - "ingesting" documents, which means embedding document hashes into the smart contract and uploadin the same documents to the web application
   - amending the names and descriptions associated with hashes (but prior history is always retained)
   - closing a smart contract, so that no further documents can be ingested

## Prerequisites

Make sure a Java 8 VM and [sbt](https://www.scala-sbt.org/) are installed on your machine.

### Deploy an instance of the smart contract

1. Clone this respository
2. Open a terminal and enter the repository directory. Type `sbt`. You will find yourself in an `sbt-ethereum` application.
   - You'll need access to the Ethereum address from which you wish to deploy the smart contract. See
     [ethKeystoreFromPrivateKeyImport](https://www.sbt-ethereum.io/tasks/eth/keystore/index.html#ethkeystorefromprivatekeyimport) and
     [ethKeystoreFromJsonImport](https://www.sbt-ethereum.io/tasks/eth/keystore/index.html#ethkeystorefromjsonimport)
   - It is helpful to give your address a human-friendly alias. For this exercise, we'll call it `docstore-manager`.
3. Switch the current session sender to your desired sender address:
   ```
   > ethAddressOverride docstore-manager
   ```
4. Make sure your address is funded
   ```
   > ethAddressBalance
   ```
5. Deploy your `DocHashStore` smart contract
   ```
   > ethTransactionDeploy DocHashStore
   ```
   Hopefully the transaction succeeds. Be sure to note th address to which it has been deployed.
   
_Hooray!_ You are done.

## Deploy your web application

The details of how you do this will depend upon the web server infrastructure that you use.

1. Download the latest binary release (as a `.tgz` or `.zip` file) to your webserver. Unpack it.
2. Edit the file `conf/application.conf`:

   ```
   ethdocstore {
     contracts {
       0x824c8956bfb388e51c2b3836d4bbf99f70120224 {                                 # A DocHashStore smart contract address. Repeat this section to manage multiple contracts.
         name            = "Sample Doc Store"                                       # an optional name for the collection of documents that will be managed
         description     = "This is just a sample. There is nothing really here."   # an optional description for the collection of documents that will be managed
         defaultToPublic = false                                                    # If set to true, documents are public by default. (Usually, you will explicitly mark contracts `public` or `private`.)
         postPutHook     = "GitAddCommitPush"                                       # If set, when new documents are ingested, the web app will try `git add .` / git commit -m "..."` / `git push`
       }
     }
     node {
       url     = "https://ropsten.infura.io/v3/bb1ca4713579450c9acb3f9d4f1f0118"    # The URL of an Ethereum JSON-RPC service for the chain on which your smart contract is deployed
       chainId = 3                                                                  # Use 1 for Ethereum mainnet! The EIP-155 Chain ID of the chain on which your smart contract is deployed
     }
     http {
       server {
         path      = "/poop/"                                                       # The location under the web app server where the app should be located. If omitted, it is root `/`
         port      = 7234                                                           # The port on which the web app will be deployed. If omitted it's `7234`.
         dataDir   = "/Users/swaldman/tmp/test-ethdocstore4"                        # The directry in which the application's data (documents, authentication information) will be stored
       }
     }
   }
   ```
   You can make `conf/application.conf` a symlink to a more convenient location.
3. Run `bin/ethdocstore` to start the web application
4. With the configuration above, you should be able to see your web application at `http://localhost:7234/` (but there won't be any documents in the collection).

Typically, in production, you will want to set this up as a service on your server, and probably proxy access to it via your main web server.

**The application uses HTTP Basic Authentication for controlling access to private documents. The URL through which you proxy access must be HTTPS, or else credentials will be abhorrently sent in the clear.**

## Create a contract-specific CLI

Just type
```
> sbt new swaldman/ethdocstore-client-seed.g8
```
You will be walked through creation of a CLI:
```
$ sbt new swaldman/ethdocstore-client-seed.g8
[warn] Executing in batch mode.
[warn]   For better performance, hit [ENTER] to switch to interactive mode, or
[warn]   consider launching sbt without any commands, or explicitly passing 'shell'
[info] Loading global plugins from /Users/swaldman/.sbt/0.13/plugins
[info] Loading project definition from /Users/swaldman/tmp/project
[info] Set current project to tmp (in build file:/Users/swaldman/tmp/)
name [Name of ethdocstore client]: sample-cli
ethdocstore_web_service_url [Base URL of docstore]: https://www.myserver.com/poop/
ethdocstore_hashstore_eth_address [Hex Ethereum address of the DocHashStore contract this CLI will manage]: 0x824c8956bfb388e51c2b3836d4bbf99f70120224
ethdocstore_chain_id [1]: 3
ethdocstore_client_plugin_version [0.0.4]: 
sbt_version [1.2.8]: 

Template applied in ./sample-cli

$ cd sample-cli
$ sbt
```

## Working with the CLI

To come!

