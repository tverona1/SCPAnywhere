const yargs = require('yargs');
const logger = require('./logger');
const siteScraper = require('./scrape-site/scraper');
const postprocess = require('./postprocess/postprocess');
const generateZips = require('./generate-zips/generate-zips')

// Path to 7z executable
const ZipExe = __dirname + '/bin/7z.exe';

/**
 * Main entry point
 */
function main() {
    logger.level = 'info';

    var argv = yargs.command('scrape', 'Scrape the site', (yargs) => {
        aliases: ['s'],
        yargs.option('o', {
            alias: 'output',
            describe: 'Output dir',
            nargs: 1
        })
        .demandOption(['o'])
    })
    .command('postprocess', 'Postprocess', (yargs) => {
		yargs.option('i', {
			alias: 'input',
			describe: 'Input path',
			nargs: 1
		})
		.demandOption(['i'])
    })
    .command('generate', 'Generate zip files', (yargs) => {
		yargs.option('i', {
			alias: 'input',
			describe: 'Input path',
			nargs: 1
		})
		.option('o', {
			alias: 'output',
			describe: 'Output path',
			nargs: 1
		})
		.option('z', {
			alias: 'zipExe',
			describe: '7-zip executable path',
            default: ZipExe,
			nargs: 1
		})
		.demandOption(['i', 'o'])
    })
    .option('l', {
        alias: 'log',
        describe: 'Set logging level',
        choices: ['debug', 'info', 'warn', 'error'],
        default: 'info'
    })
    .demandCommand(1, 'Please specify at least one command')
    .strict()
    .help('h')
    .alias('h', 'help')
    .argv;

    logger.level = argv.log ?? 'info';

    const command = argv._[0];

    if ('scrape' == command) {
        siteScraper(argv);
    } else if ('postprocess' == command) {
        postprocess(argv);
    } else {
        generateZips(argv);
    }
}

main();
