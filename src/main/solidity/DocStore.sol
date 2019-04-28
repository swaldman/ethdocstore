pragma solidity ^0.5.7;

contract DocHashStore {
  struct Record {
    uint    timestamp;
    string  name;
    string  description;
    address filer;
    address lastAmender;
    uint    recordLastModifiedBlockNumber;
  }

  event Stored( uint timestamp, string name, string description, address filer );
  event Amended( string name, string description, address updater, uint priorUpdateBlockNumber );

  address public admin;
  bytes32[] public docHashes;
  mapping ( bytes32 => Record ) private records;
  mapping ( address => bool ) public authorized;
  uint public openTime;
  uint public closeTime;
  bool public closed;

  constructor() public {
    admin    = msg.sender;
    openTime = block.timestamp;
    closed   = false;
  }

  modifier onlyAdmin {
    require( msg.sender == admin, "Administrator access only." );
    _;
  }

  function close() public onlyAdmin {
    closeTime = block.timestamp;
    closed = true;
  }

  function authorize( address filer ) public onlyAdmin {
    authorized[ filer ] = true;
  }

  function deauthorize( address filer ) public onlyAdmin {
    authorized[ filer ] = false;
  }

  function store( bytes32 docHash, string memory name, string memory description ) public {
    require( !closed, "This DocHashStore has been closed." );
    require( msg.sender == admin || authorized[ msg.sender ], "The sender is not authorized to modiy this DocHashStore." );
    require( records[ docHash ].timestamp == 0, "DocHash has already been stored." );
    
    records[docHash] = Record( block.timestamp, name, description, msg.sender, msg.sender, block.number );
    docHashes.push( docHash );

    emit Stored( block.timestamp, name, description, msg.sender );
  }
  
  function amend( bytes32 docHash, string memory name, string memory description ) public {
    require( !closed, "This DocHashStore has been closed." );
    require( msg.sender == admin || authorized[ msg.sender ], "The sender is not authorized to modiy this DocHashStore." );
    require( records[ docHash ].timestamp != 0, "DocHash has not been defined, must be stored, can't be amended." );

    Record memory oldRecord = records[docHash]; // important that we copy to memory!
    Record memory newRecord = Record( oldRecord.timestamp, name, description, oldRecord.filer, msg.sender, block.number );
    
    records[docHash] = newRecord;

    emit Amended( name, description, msg.sender, oldRecord.recordLastModifiedBlockNumber );
  }
  
  function isStored( bytes32 docHash ) public view returns (bool) {
    return (records[ docHash ].timestamp != 0);
  }
  
  function timestamp( bytes32 docHash ) public view returns (uint) {
    return records[ docHash ].timestamp;
  }
  
  function name( bytes32 docHash ) public view returns (string memory) {
    return records[ docHash ].name;
  }
  
  function description( bytes32 docHash ) public view returns (string memory) {
    return records[ docHash ].description;
  }
  
  function filer( bytes32 docHash ) public view returns (address) {
    return records[ docHash ].filer;
  }
}