pragma solidity ^0.5.7;

contract DocHashStore {
  struct Record {
    uint    timestamp;
    string  name;
    string  description;
    address filer;
  }

  address public admin;
  bytes32[] public docHashes;
  mapping ( bytes32 => Record ) private records;
  mapping ( address => bool ) public authorized;
  bool public closed;

  constructor( address _admin ) public {
    admin = _admin;
    closed = false;
  }

  modifier onlyAdmin {
    require( msg.sender == admin, "Administrator access only." );
    _;
  }

  function close() public onlyAdmin {
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
    
    records[docHash] = Record( block.timestamp, name, description, msg.sender );
    docHashes.push( docHash );
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