import eacl = require('@eacl/eacl');
import eacl_express = require('./eacl/express');

import express = require('express');

let app = express();

app.get('/', function (req, res) {

});


test('test123', async done => {
    expect(eacl.can({})).toBe(false);
    //expect(true).toBe(false);
    done();
});

app.listen(3005);