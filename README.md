# ethdocstore

* [ethdocstore](#ethdocstore)
  * [Introduction](#introduction)
  * [Prerequisites](#prerequisites)
    * [Deploy an instance of the smart contract](#deploy-an-instance-of-the-smart-contract)
  * [Deploy your web application](#deploy-your-web-application)
  * [Create a contract\-specific CLI](#create-a-contract-specific-cli)
  * [Working with the CLI](#working-with-the-cli)
    * [Ingesting documents](#ingesting-documents)
      * [docstoreIngestFile](#docstoreingestfile)
    * [Authorizing and deauthorizing addresses](#authorizing-and-deauthorizing-addresses)
      * [docstoreAuthorizeAddress](#docstoreauthorizeaddress)

## Introduction

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
3. [Switch the current session sender](https://www.sbt-ethereum.io/tasks/eth/address/sender.html#ethaddresssenderoverrideset) to your desired sender address:
   ```
   > ethAddressOverride docstore-manager
   ```
   _Note: This address, the deployer address, will become the administrator of the contract._
4. Make sure your address is funded
   ```
   > ethAddressBalance
   ```
5. Deploy your `DocHashStore` smart contract
   ```
   > ethTransactionDeploy DocHashStore
   ```
   Hopefully the transaction succeeds. Be sure to note the address to which it has been deployed.

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
sbt:ethdocstore> 
```

## Working with the CLI

Some general notes"
* Command names are long, but they are tab-completable.
* You often have to approve transactions twice (once to sign them, again to submit them). This is annoying, and may be fixed in the future.

### Ingesting documents

The purpose of `ethdocstore` is to maintain a list of web-accessible documents synchronized with their blockchain-store hashes.
In general, the first thing you want to do with it is "ingest" some documents, which means hash them and store the
hash on the blockchain while uploading them them to the web service.

Make sure that your instance of the `ethdocstore` web application is up and running. You won't be able to ingest documents without it.

#### docstoreIngestFile

_This command will only succeed if the current sender is set to the administrator (deployer) or other authorized address._

**Usage:**
```
> docstoreIngestFile <pdf|jpeg|tiff|html|text|zip|arbitrary-mime-type>
```

**Example:**
```
> docstoreIngestFile 
*/*    html   jpeg   pdf    text   tiff   zip    
sbt:ethdocstore-test-client> docstoreIngestFile pdf
Full path to file: /Users/swaldman/Dropbox/BaseFolders/mchange-llc-docs/IRS/mchange-llc-EIN-letter.pdf
Name (or [Enter] for 'mchange-llc-EIN-letter.pdf'): IRS EIN Assignment
Description: IRS EIN assignment for Machinery For Change, LLC.
Should this file be public? [y/n] n
[info] Checking file: '/Users/swaldman/Dropbox/BaseFolders/mchange-llc-docs/IRS/mchange-llc-EIN-letter.pdf'
[info] File '/Users/swaldman/Dropbox/BaseFolders/mchange-llc-docs/IRS/mchange-llc-EIN-letter.pdf' successfully uploaded.

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x824c8956bfb388e51c2b3836d4bbf99f70120224 (with aliases ['test-doc-hash-store4'] on chain with ID 3)
==>   From:  0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c (with aliases ['default-sender','testing5'] on chain with ID 3)
==>   Data:  0x55be1d55f74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861000000000000000000000000000000000000000000000000000000000000006000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000124952532045494e2041737369676e6d656e74000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000314952532045494e2061737369676e6d656e7420666f72204d616368696e65727920466f72204368616e67652c204c4c432e000000000000000000000000000000
==>   Value: 0 ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: store(bytes32,string,string)
==>     Arg 1 [name=docHash, type=bytes32]: 0xf74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861
==>     Arg 2 [name=name, type=string]: "IRS EIN Assignment"
==>     Arg 3 [name=description, type=string]: "IRS EIN assignment for Machinery For Change, LLC."
==>
==> The nonce of the transaction would be 65.
==>
==> $$$ The transaction you have requested could use up to 266572 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000266572 ether.
==> $$$ (No USD value could be determined for ETH on chain with ID 3 from Coinbase).

Are you sure it is okay to sign this transaction as '0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c' (with aliases ['default-sender','testing5'] on chain with ID 3)? [y/n] y
[info] Unlocking address '0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c' (on chain with ID 3, aliases ['default-sender','testing5'])
Enter passphrase or hex private key for address '0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c': *******************
[info] V3 wallet(s) found for '0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c' (aliases ['default-sender','testing5'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x824c8956bfb388e51c2b3836d4bbf99f70120224 (with aliases ['test-doc-hash-store4'] on chain with ID 3)
==>   From:  0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c (with aliases ['default-sender','testing5'] on chain with ID 3)
==>   Data:  0x55be1d55f74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861000000000000000000000000000000000000000000000000000000000000006000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000124952532045494e2041737369676e6d656e74000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000314952532045494e2061737369676e6d656e7420666f72204d616368696e65727920466f72204368616e67652c204c4c432e000000000000000000000000000000
==>   Value: 0 ether
==>
==> The transaction is signed with Chain ID 3 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: store(bytes32,string,string)
==>     Arg 1 [name=docHash, type=bytes32]: 0xf74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861
==>     Arg 2 [name=name, type=string]: "IRS EIN Assignment"
==>     Arg 3 [name=description, type=string]: "IRS EIN assignment for Machinery For Change, LLC."
==>
==> The nonce of the transaction would be 65.
==>
==> $$$ The transaction you have requested could use up to 266572 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000266572 ether.
==> $$$ (No USD value could be determined for ETH on chain with ID 3 from Coinbase).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x43c9f580ac8d1fec16c403b45e807f79ef42d6c5199fc7cb2fe0f136f2f82dd5' will be submitted. Please wait.
[info] Successfully ingested '/Users/swaldman/Dropbox/BaseFolders/mchange-llc-docs/IRS/mchange-llc-EIN-letter.pdf' with hash '0xf74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861' and content type 'application/pdf'
[success] Total time: 272 s, completed Sep 3, 2019 1:08:07 PM
```

### Authorizing and deauthorizing addresses

Initially a `DocHashStore` has just an administrator, which is the address that deployed the contract. The administrator cannot be changed. (However, a contract can be closed,
and a new contract deployed by a new administrator.)

Administrators can authorize new Ethereum address, which then have permission to ingest new documents, to amend document names and descriptions, and to view private documents.
Any authorized users may also close a `DocHashStore`, rendering it read-only. (This is a bare-bones, extremely course-grained permissions system, governed above all else by KISS.)

#### docstoreAuthorizeAddress

**Usage:**
```
> docstoreAuthorizeAddress <address-as-hex-ens-or-alias>
```

**Example:***
```
> docstoreAuthorizeAddress 0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x824c8956bfb388e51c2b3836d4bbf99f70120224 (with aliases ['test-doc-hash-store4'] on chain with ID 3)
==>   From:  0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c (with aliases ['default-sender','testing5'] on chain with ID 3)
==>   Data:  0xb6a5d7de000000000000000000000000e92db74ce7c392634a1d9af344aeeb4a5f1e0a78
==>   Value: 0 ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: authorize(address)
==>     Arg 1 [name=filer, type=address]: 0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78
==>
==> The nonce of the transaction would be 66.
==>
==> $$$ The transaction you have requested could use up to 53516 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000053516 ether.
==> $$$ (No USD value could be determined for ETH on chain with ID 3 from Coinbase).

Are you sure it is okay to sign this transaction as '0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c' (with aliases ['default-sender','testing5'] on chain with ID 3)? [y/n] y
[info] Unlocking address '0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c' (on chain with ID 3, aliases ['default-sender','testing5'])
Enter passphrase or hex private key for address '0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c': *******************
[info] V3 wallet(s) found for '0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c' (aliases ['default-sender','testing5'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x824c8956bfb388e51c2b3836d4bbf99f70120224 (with aliases ['test-doc-hash-store4'] on chain with ID 3)
==>   From:  0x2bf2f238050ff93773b9d3b33c61cb3ba700ba6c (with aliases ['default-sender','testing5'] on chain with ID 3)
==>   Data:  0xb6a5d7de000000000000000000000000e92db74ce7c392634a1d9af344aeeb4a5f1e0a78
==>   Value: 0 ether
==>
==> The transaction is signed with Chain ID 3 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: authorize(address)
==>     Arg 1 [name=filer, type=address]: 0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78
==>
==> The nonce of the transaction would be 66.
==>
==> $$$ The transaction you have requested could use up to 53516 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000053516 ether.
==> $$$ (No USD value could be determined for ETH on chain with ID 3 from Coinbase).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xaa23182a3fe512a0bdd3813f18905087c42221b96b2ced8ecb3d580baab4bbff' will be submitted. Please wait.
[info] Address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' has been successfully authorized on the DocHashStore at address '0x824c8956bfb388e51c2b3836d4bbf99f70120224'.
[success] Total time: 22 s, completed Sep 3, 2019 1:42:04 PM
```
#### docstoreDeauthorizeAddress

**Usage:**
```
> docstoreDeauthorizeAddress <address-as-hex-ens-or-alias>
```

### Registering a web application username and password

In order to view private documents, administrators and authorized users will need to register a username / password pair
for HTTP Basic authentication. **HTTP Basic passwords are sent as cleartext, so the web application should only be
deployed over TLS/HTTPS!** Authentication information is sanely stored on the server as salted BCrypt hashes.

Before you can view private documents, you'll have to, register via the CLI  as an authorized user (or administrator).
You'll be asked to sign a challenge (that could not be a transaction!) to prove you control the key of the current session sender address.
The server will then map the username you register to that address, and allow it access to private documents if the Ethereum address
is authorized.

Once you have registered, just supply the username and password associated with an authorized Ethereum address to view private documents.

#### docstoreRegisterUser

**Usage:**
```
> docstoreRegisterUser
```

**Example:**
```
docstoreRegisterUser
You would be registering as '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78'. Is that okay? [y/n] y
Username: bozo
Password: **********
Confirm password: **********
You will be asked to sign a challenge in order to prove you are associated with address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78'.
[info] Challenge successfully received.

==> D O C U M E N T   S I G N A T U R E   R E Q U E S T
==>
==> The signing address would be '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (with aliases ['testing4'] on chain with ID 3).
==>
==> This data does not appear to be a transaction for chain with ID 3.
==>
==> Raw data: 0x5369676e6174757265204368616c6c656e6765202d2052616e646f6d2042797465733a20e139db9a686b87594d524a69ee6ec7b8e7519a68b0afed4e5c8c3fda6a695896
==>
==> Raw data interpreted as as UTF8 String: "Signature Challenge - Random Bytes: �9ۚhk�YMRJi�nǸ�Q�h���N\\�\?�jiX�"


Are you sure it is okay to sign this data as '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (with aliases ['testing4'] on chain with ID 3)? [y/n] y
[info] Unlocking address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (on chain with ID 3, aliases ['testing4'])
Enter passphrase or hex private key for address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78': *******************
[info] V3 wallet(s) found for '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (aliases ['testing4'])
[info] Registration accepted.
[info] User 'bozo' successfully registered as '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78'.
[success] Total time: 41 s, completed Sep 3, 2019 1:50:55 PM
docstoreRegisterUser
You would be registering as '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78'. Is that okay? [y/n] y
Username: bozo
Password: **********
Confirm password: **********
You will be asked to sign a challenge in order to prove you are associated with address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78'.
[info] Challenge successfully received.

==> D O C U M E N T   S I G N A T U R E   R E Q U E S T
==>
==> The signing address would be '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (with aliases ['testing4'] on chain with ID 3).
==>
==> This data does not appear to be a transaction for chain with ID 3.
==>
==> Raw data: 0x5369676e6174757265204368616c6c656e6765202d2052616e646f6d2042797465733a20e139db9a686b87594d524a69ee6ec7b8e7519a68b0afed4e5c8c3fda6a695896
==>
==> Raw data interpreted as as UTF8 String: "Signature Challenge - Random Bytes: �9ۚhk�YMRJi�nǸ�Q�h���N\\�\?�jiX�"


Are you sure it is okay to sign this data as '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (with aliases ['testing4'] on chain with ID 3)? [y/n] y
[info] Unlocking address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (on chain with ID 3, aliases ['testing4'])
Enter passphrase or hex private key for address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78': *******************
[info] V3 wallet(s) found for '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (aliases ['testing4'])
[info] Registration accepted.
[info] User 'bozo' successfully registered as '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78'.
[success] Total time: 41 s, completed Sep 3, 2019 1:50:55 PM
```

### Amending document names and descriptions

Although document hashes, once a document as been [ingested](#ingesting-documents) are immutable and unchangeable, the names
and descriptions associated with those hashes can be modified by authorized users.

_Note: Prior versions of names and descriptions are not deleted. They remain available in the prior state of the blockchain and in event logs produced by the contract._

**Usage:**
```
> docstoreAmend
```

**Example:**
```
> docstoreAmend 0xf74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861
Name (or [Enter] for 'IRS EIN Assignment'): 
Description (or [Enter] to leave the description unchanged): Internal Revenue Service EIN assignment for Machinery For Change, LLC.

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x824c8956bfb388e51c2b3836d4bbf99f70120224 (with aliases ['test-doc-hash-store4'] on chain with ID 3)
==>   From:  0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78 (with aliases ['testing4'] on chain with ID 3)
==>   Data:  0x8ed902b9f74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861000000000000000000000000000000000000000000000000000000000000006000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000124952532045494e2041737369676e6d656e7400000000000000000000000000000000000000000000000000000000000000000000000000000000000000000046496e7465726e616c20526576656e756520536572766963652045494e2061737369676e6d656e7420666f72204d616368696e65727920466f72204368616e67652c204c4c432e0000000000000000000000000000000000000000000000000000
==>   Value: 0 ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: amend(bytes32,string,string)
==>     Arg 1 [name=docHash, type=bytes32]: 0xf74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861
==>     Arg 2 [name=name, type=string]: "IRS EIN Assignment"
==>     Arg 3 [name=description, type=string]: "Internal Revenue Service EIN assignment for Machinery For Change, LLC."
==>
==> The nonce of the transaction would be 3.
==>
==> $$$ The transaction you have requested could use up to 129042 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000129042 ether.
==> $$$ (No USD value could be determined for ETH on chain with ID 3 from Coinbase).

Are you sure it is okay to sign this transaction as '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (with aliases ['testing4'] on chain with ID 3)? [y/n] y
[info] Unlocking address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (on chain with ID 3, aliases ['testing4'])
Enter passphrase or hex private key for address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78': *******************
[info] V3 wallet(s) found for '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (aliases ['testing4'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x824c8956bfb388e51c2b3836d4bbf99f70120224 (with aliases ['test-doc-hash-store4'] on chain with ID 3)
==>   From:  0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78 (with aliases ['testing4'] on chain with ID 3)
==>   Data:  0x8ed902b9f74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861000000000000000000000000000000000000000000000000000000000000006000000000000000000000000000000000000000000000000000000000000000a000000000000000000000000000000000000000000000000000000000000000124952532045494e2041737369676e6d656e7400000000000000000000000000000000000000000000000000000000000000000000000000000000000000000046496e7465726e616c20526576656e756520536572766963652045494e2061737369676e6d656e7420666f72204d616368696e65727920466f72204368616e67652c204c4c432e0000000000000000000000000000000000000000000000000000
==>   Value: 0 ether
==>
==> The transaction is signed with Chain ID 3 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: amend(bytes32,string,string)
==>     Arg 1 [name=docHash, type=bytes32]: 0xf74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861
==>     Arg 2 [name=name, type=string]: "IRS EIN Assignment"
==>     Arg 3 [name=description, type=string]: "Internal Revenue Service EIN assignment for Machinery For Change, LLC."
==>
==> The nonce of the transaction would be 3.
==>
==> $$$ The transaction you have requested could use up to 129042 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000129042 ether.
==> $$$ (No USD value could be determined for ETH on chain with ID 3 from Coinbase).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x89c83dda4d1b8e3b7245a4a295073243c3d0e67476d08c274b56257fdc3a1a63' will be submitted. Please wait.
[info] Successfully amended name and description for file with hash '0xf74f0c0ed67097c447308af808497e3d02d28240edd357bb57a9555fb0825861'.
[success] Total time: 72 s, completed Sep 3, 2019 2:14:34 PM
```

### Closing a docstore

The administrator or any authorized user can close a docstore at any time. Once closed, the docstore cannot modified.
It is easy to create new docstores, so closing one is not so big a deal.
You might consider closing docstores for organizational reasons, for example, like keeping separate docstore by year.

**Usage:**

```
> docstoreClose
```

**Example:**
```
> docstoreClose
Are you sure you want to permanently close the DocHashStore at address '0x824c8956bfb388e51c2b3836d4bbf99f70120224'? [y/n] y

==> T R A N S A C T I O N   S I G N A T U R E   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x824c8956bfb388e51c2b3836d4bbf99f70120224 (with aliases ['test-doc-hash-store4'] on chain with ID 3)
==>   From:  0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78 (with aliases ['testing4'] on chain with ID 3)
==>   Data:  0x43d726d6
==>   Value: 0 ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: close()
==>
==> The nonce of the transaction would be 4.
==>
==> $$$ The transaction you have requested could use up to 76342 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000076342 ether.
==> $$$ (No USD value could be determined for ETH on chain with ID 3 from Coinbase).

Are you sure it is okay to sign this transaction as '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (with aliases ['testing4'] on chain with ID 3)? [y/n] y
[info] Unlocking address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (on chain with ID 3, aliases ['testing4'])
Enter passphrase or hex private key for address '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78': *******************
[info] V3 wallet(s) found for '0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78' (aliases ['testing4'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x824c8956bfb388e51c2b3836d4bbf99f70120224 (with aliases ['test-doc-hash-store4'] on chain with ID 3)
==>   From:  0xe92db74ce7c392634a1d9af344aeeb4a5f1e0a78 (with aliases ['testing4'] on chain with ID 3)
==>   Data:  0x43d726d6
==>   Value: 0 ether
==>
==> The transaction is signed with Chain ID 3 (which correctly matches the current session's 'ethNodeChainId').
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: close()
==>
==> The nonce of the transaction would be 4.
==>
==> $$$ The transaction you have requested could use up to 76342 units of gas.
==> $$$ You would pay 1 gwei for each unit of gas, for a maximum cost of 0.000076342 ether.
==> $$$ (No USD value could be determined for ETH on chain with ID 3 from Coinbase).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0x59e7fb3d54f1d9c2131fab8b4ad3fc1f8c2bd5cc10bec5f6a501e121fdacbe83' will be submitted. Please wait.
[info] DocHashStore at address '0x824c8956bfb388e51c2b3836d4bbf99f70120224' has been permanently closed.
[success] Total time: 32 s, completed Sep 3, 2019 2:23:38 PM

```




