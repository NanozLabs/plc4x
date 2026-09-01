/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// CJ/T 188-2004 Protocol Specification
// Household meter data-transmission technical conditions
// (water / heat / gas meters)

[constants
    [const uint 8 cjt188FrameStart 0x68]
    [const uint 8 cjt188FrameEnd 0x16]
]

[enum uint 8 ControlCode
    // Read data (读数据)
    ['0x01' READ_DATA                          ]
    ['0x81' READ_DATA_RESPONSE                 ]
    ['0xA1' READ_DATA_RESPONSE_MORE            ]
    ['0xC1' READ_DATA_ERROR                    ]
    // Read address (读地址)
    ['0x03' READ_ADDRESS                       ]
    ['0x83' READ_ADDRESS_RESPONSE              ]
    ['0xC3' READ_ADDRESS_ERROR                 ]
    // Write data (写数据)
    ['0x04' WRITE_DATA                         ]
    ['0x84' WRITE_DATA_RESPONSE                ]
    ['0xC4' WRITE_DATA_ERROR                   ]
    // Write address (写地址)
    ['0x15' WRITE_ADDRESS                      ]
    ['0x95' WRITE_ADDRESS_RESPONSE             ]
    ['0xD5' WRITE_ADDRESS_ERROR                ]
    // Write electromechanical sync data (写机电同步数据)
    ['0x16' WRITE_SYNC                         ]
    ['0x96' WRITE_SYNC_RESPONSE                ]
    ['0xD6' WRITE_SYNC_ERROR                   ]
]

// Main frame type per CJ/T 188-2004 Section 6.
// Address is 7 bytes: A0 = meter type, A1..A6 = 12-digit BCD (low byte first).
// Checksum CS = mod-256 sum of all bytes from first 0x68 to before CS.
// Data field is stored decoded (-0x33) in memory; checksum uses STATIC_CALL with parsed fields.
[type Cjt188Frame(bit response) byteOrder='BIG_ENDIAN'
    [const         uint 8      start1   0x68]
    [array         byte        address  count '7']
    [const         uint 8      start2   0x68]
    [simple        ControlCode control       ]
    [simple        uint 8      length        ]
    [manualArray   byte        dataPlain terminated
        'COUNT(_values) == length'
        'STATIC_CALL("parseDataByteMinus33", readBuffer)'
        'STATIC_CALL("serializeDataBytePlus33", writeBuffer, _value)'
        'length'
    ]
    [checksum      uint 8      cs       'STATIC_CALL("calcCs", address, control, length, dataPlain)']
    [const         uint 8      end      0x16]
]
