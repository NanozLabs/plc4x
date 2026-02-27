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

// DL/T 645-2007 Protocol Specification
// China Smart Meter Communication Protocol

[constants
    [const uint 8 dlt645FrameStart 0x68]
    [const uint 8 dlt645FrameEnd 0x16]
]

[enum uint 8 ControlCode
    // Read data (读数据)
    ['0x11' READ_DATA                          ]
    ['0x91' READ_DATA_RESPONSE                 ]
    ['0xB1' READ_DATA_RESPONSE_MORE            ]
    ['0xD1' READ_DATA_ERROR                    ]
    // Read subsequent data (读后续数据)
    ['0x12' READ_SUBSEQUENT_DATA               ]
    ['0x92' READ_SUBSEQUENT_DATA_RESPONSE      ]
    ['0xB2' READ_SUBSEQUENT_DATA_RESPONSE_MORE ]
    ['0xD2' READ_SUBSEQUENT_DATA_ERROR         ]
    // Read communication address (读通信地址)
    ['0x13' READ_ADDRESS                       ]
    ['0x93' READ_ADDRESS_RESPONSE              ]
    ['0xD3' READ_ADDRESS_ERROR                 ]
    // Write data (写数据)
    ['0x14' WRITE_DATA                         ]
    ['0x94' WRITE_DATA_RESPONSE                ]
    ['0xD4' WRITE_DATA_ERROR                   ]
    // Write communication address (写通信地址)
    ['0x15' WRITE_ADDRESS                      ]
    ['0x95' WRITE_ADDRESS_RESPONSE             ]
    ['0xD5' WRITE_ADDRESS_ERROR                ]
    // Freeze data (冻结命令)
    ['0x16' FREEZE_DATA                        ]
    ['0x96' FREEZE_DATA_RESPONSE               ]
    ['0xD6' FREEZE_DATA_ERROR                  ]
    // Change baud rate (更改通信速率)
    ['0x17' CHANGE_BAUD_RATE                   ]
    ['0x97' CHANGE_BAUD_RATE_RESPONSE          ]
    ['0xD7' CHANGE_BAUD_RATE_ERROR             ]
    // Change password (修改密码)
    ['0x18' CHANGE_PASSWORD                    ]
    ['0x98' CHANGE_PASSWORD_RESPONSE           ]
    ['0xD8' CHANGE_PASSWORD_ERROR              ]
    // Clear max demand (清零最大需量)
    ['0x19' CLEAR_MAX_DEMAND                   ]
    ['0x99' CLEAR_MAX_DEMAND_RESPONSE          ]
    ['0xD9' CLEAR_MAX_DEMAND_ERROR             ]
    // Clear meter (电表清零)
    ['0x1A' CLEAR_METER                        ]
    ['0x9A' CLEAR_METER_RESPONSE               ]
    ['0xDA' CLEAR_METER_ERROR                  ]
    // Clear event (事件清零)
    ['0x1B' CLEAR_EVENT                        ]
    ['0x9B' CLEAR_EVENT_RESPONSE               ]
    ['0xDB' CLEAR_EVENT_ERROR                  ]
    // Broadcast time sync (广播校时)
    ['0x08' BROADCAST_TIME_SYNC                ]
    ['0x88' BROADCAST_TIME_SYNC_RESPONSE       ]
]

// Main frame type per DL/T 645-2007 Section 4.2.
// Checksum CS = mod-256 sum of all bytes from first 0x68 to before CS (standard-compliant).
// Data field is stored decoded (-0x33) in memory; checksum uses STATIC_CALL with parsed fields.
[type Dlt645Frame(bit response) byteOrder='BIG_ENDIAN'
    [const         uint 8      start1   0x68]
    [array         byte        address  count '6']
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
