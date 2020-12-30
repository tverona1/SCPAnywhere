const fs = require('fs');
const fsP = fs.promises;
const path = require('path');

/**
 * Constructs regexp from url path
 * 
 * @param {string} base - base url
 * @param {string} path - url path
 */
function getUrlRegExp(base, path) {
	const RegExpEnd = '(?:/|$)';
	return new RegExp(base + (path ?? '') + RegExpEnd, 'ig');
}

/**
 * Recursively enumerates directory and returns entries
 * 
 * @param {string} dir - path to enumerate
 * @param {regexp} filter - filter regexp
 */
async function findInDir(dir, filter) {
	var ret = [];

	await findInDirInternal(dir, filter);

	async function findInDirInternal (dir, filter) {
		const files = await fsP.readdir(dir);
		for (var i = 0; i < files.length; i++) {
			const filePath = path.join(dir, files[i]);
			const fileStat = fs.lstatSync(filePath);

			if (fileStat.isDirectory()) {
				await findInDirInternal(filePath, filter);
		    } else if (filePath.match(filter)) {
		    	ret.push(filePath);
		    }
		}
	}

	return ret;
}

/**
 * Binary searches a sorted array
 * 
 * @param {array} sortedArray - sorted array to search
 * @param {object} key - key to look for
 * @param {function} compare_fn - compare function
 */
function binarySearchSortedArray(sortedArray, key, compare_fn) {
    var m = 0;
    var n = sortedArray.length - 1;
    while (m <= n) {
        var k = (n + m) >> 1;
        var cmp = compare_fn(key, sortedArray[k]);
        if (cmp > 0) {
            m = k + 1;
        } else if(cmp < 0) {
            n = k - 1;
        } else {
            return k;
        }
    }
    return -1;
}

module.exports = {
	getUrlRegExp,
	findInDir,
	binarySearchSortedArray
};